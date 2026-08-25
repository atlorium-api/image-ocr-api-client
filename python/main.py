"""
Клиент API распознавания текста с изображения (OCR) Atlorium — страница документа → Markdown.

Запуск (работает сразу, без регистрации — на демо-ключе):
    pip install -r requirements.txt
    python main.py
    python main.py ../sample.png
    python main.py ../sample.png --mode digits

Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
ATLORIUM_API_KEY. Код при этом не меняется.
"""

import base64
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import requests

# Публичный демо-ключ. С ним API отвечает МОКОМ: возвращается сгенерированная
# страница документа, а НЕ результат настоящего распознавания вашей картинки.
# Ответ детерминирован (seed берётся из самой картинки), поэтому на нём можно
# писать стабильные тесты — но качество распознавания он не показывает.
SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1"

API_KEY = os.environ.get("ATLORIUM_API_KEY", SANDBOX_KEY)
BASE_URL = os.environ.get("ATLORIUM_BASE_URL", "https://atlorium.com")

# Распознавание синхронное: ответ приходит в том же HTTP-вызове, и на плотной
# странице это занимает секунды. Таймаут с запасом.
TIMEOUT = 120

RETRY_DELAY = 20
MAX_RETRIES = 1

# Потолок ожидания при 429. Исчерпав часовое окно, сервер честно просит подождать
# десятки минут — клиент, слепо доверяющий Retry-After, зависнет на всё это время
# (а в CI просто съест бюджет джоба). Дольше потолка не ждём: честно сообщаем,
# что квота исчерпана, и выходим.
MAX_RETRY_DELAY = 120

# Образец лежит в КОРНЕ репозитория — один на все шесть примеров.
DEFAULT_IMAGE = Path(__file__).resolve().parent.parent / "sample.png"

# Верхняя граница размера изображения на стороне сервиса — 10 МБ в декодированном
# виде. Проверяем локально, чтобы не тратить запрос на заведомый 400.
MAX_IMAGE_BYTES = 10 * 1024 * 1024

# Сколько строк распознанного текста печатать. Страница документа — это десятки
# строк; в консоль выводим начало, остальное остаётся в result.text.
PREVIEW_LINES = 40


class AtloriumError(RuntimeError):
    """Ошибка API. Код HTTP разложен в человекочитаемую причину."""

    REASONS = {
        400: "Изображение не передано, битый Base64 или размер больше 10 МБ "
             "(запрос НЕ тарифицируется)",
        401: "API-ключ отсутствует, просрочен или недействителен",
        402: "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429: "Превышен лимит запросов — повторите позже",
        503: "Сервис распознавания временно недоступен "
             "(за сбой на своей стороне мы не списываем деньги)",
    }

    def __init__(self, status: int, body: str):
        reason = self.REASONS.get(status, "Неизвестная ошибка")
        super().__init__(f"HTTP {status}: {reason}. Ответ сервера: {body[:200]}")
        self.status = status


def retry_after(response: requests.Response) -> int:
    """Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру.

    Значение 0 означало бы «повторяй немедленно» — клиент ушёл бы в busy-loop.
    Значение в десятки минут (так сервер отвечает на исчерпанное часовое окно)
    означало бы «спи почти час». Возвращаем 0, если ждать бессмысленно долго:
    вызывающий сдастся и сообщит об этом.
    """
    raw = response.headers.get("Retry-After", "")
    try:
        seconds = int(raw)
    except ValueError:
        seconds = 0

    if seconds <= 0:
        return RETRY_DELAY
    return seconds if seconds <= MAX_RETRY_DELAY else 0


# Сигнатуры растровых форматов, которые сервис понимает. PDF в списке нет намеренно:
# это не изображение, и отдельная ветка ниже сообщает об этом внятно, а не «не похоже
# на картинку».
IMAGE_SIGNATURES = (
    (b"\x89PNG\r\n\x1a\n", "PNG"),
    (b"\xff\xd8\xff", "JPEG"),
    (b"GIF8", "GIF"),
    (b"BM", "BMP"),
    (b"II*\x00", "TIFF"),
    (b"MM\x00*", "TIFF"),
)


def image_format(data: bytes) -> str | None:
    """Определяет формат по сигнатуре файла. Сервис принимает ТОЛЬКО изображения."""
    if data[:4] == b"%PDF":
        # Отдельная ветка: PDF присылают чаще всего, и ошибка должна быть внятной.
        return "PDF"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "WEBP"
    for signature, name in IMAGE_SIGNATURES:
        if data.startswith(signature):
            return name
    return None


def image_to_text(image: bytes, mode: str = "auto") -> dict:
    """POST /api/Ocr/image-to-text — единственный эндпоинт сервиса.

    Тело запроса (ImageOcrRequest):
      image — изображение страницы в Base64. Принимается и «голый» base64,
              и data-URL вида "data:image/png;base64,...".
      mode  — режим распознавания:
                "auto"     — сервис сам различает короткую строку и страницу;
                "document" — страница целиком, с сохранением структуры;
                "table"    — только таблица;
                "formula"  — формула, ответ в разметке LaTeX;
                "line"     — короткая строка из букв и цифр;
                "digits"   — короткая строка из одних цифр.
              Неизвестное значение сервис отвергает с кодом 400, а не подменяет
              молча: распознать не в том режиме — значит выставить счёт за
              работу, которую не просили.
    """
    payload: dict[str, object] = {
        "image": base64.b64encode(image).decode("ascii"),
        "mode": mode,
    }

    for attempt in range(MAX_RETRIES + 1):
        response = requests.post(
            f"{BASE_URL}/api/Ocr/image-to-text",
            json=payload,
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Accept": "application/json",
            },
            timeout=TIMEOUT,
        )

        # 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
        if response.status_code == 429 and attempt < MAX_RETRIES:
            delay = retry_after(response)
            if delay == 0:
                raise AtloriumError(429, "лимит по IP исчерпан, повторите позже")
            print(f"  ... лимит запросов, пауза {delay} с", file=sys.stderr)
            time.sleep(delay)
            continue

        if not response.ok:
            raise AtloriumError(response.status_code, response.text)
        return response.json()

    raise AtloriumError(429, "лимит запросов не отпустил после повтора")


# ── Применение данных: разбор распознанной страницы ───────────────────────────
# Ответ сам по себе — просто JSON. Ценность появляется, когда по нему принимают
# решение. Здесь решение принимается по трём полям сразу:
#
#   recognized — читаемый текст найден. Плата берётся ТОЛЬКО за recognized=true;
#                recognized=false — деньги не списаны, изображение можно улучшить
#                и отправить снова, ничего не заплатив.
#   format     — чем является text: "markdown" (страница с заголовками, абзацами,
#                таблицами и формулами), "plain" (простая строка) или "latex"
#                (формула). Список значений может пополниться, поэтому НЕизвестное
#                значение разбираем как "plain" — так советует сам контракт.
#   units      — во сколько единиц работы обошёлся запрос: по ним он и
#                тарифицирован. Короткая строка — всегда 1, страница — столько,
#                сколько на ней распознано областей. Позволяет свести расход по
#                пакету, не заглядывая в личный кабинет.
#   truncated  — ответ оборван по длине, страница распознана НЕ ПОЛНОСТЬЮ. Это
#                самое коварное поле: обрезанный текст выглядит совершенно
#                нормальным, и без явной проверки потеря части документа пройдёт
#                незамеченной. Поэтому здесь она поднимается до вердикта
#                «требуется ручная проверка», а не прячется в лог.


@dataclass
class Layout:
    """Структура распознанной страницы — считается по Markdown-разметке ответа."""

    headings: int
    table_rows: int
    lines: int


@dataclass
class Recognized:
    recognized: bool
    text: str | None
    fmt: str
    truncated: bool
    elapsed_ms: int
    # Режим, в котором изображение распознано НА САМОМ ДЕЛЕ. Совпадает с
    # запрошенным, кроме "auto" — там видно решение сервиса. Если результат
    # оказался не тем, что ожидался, смотреть надо в первую очередь сюда.
    mode: str
    # Единицы работы, в которые обошёлся запрос, — по ним он и тарифицирован.
    units: int
    source: Path
    size_bytes: int
    image_format: str
    layout: Layout | None

    @property
    def charged(self) -> bool:
        """Тарифицируется ли запрос. Нераспознанное изображение — бесплатно."""
        return self.recognized

    @property
    def is_markdown(self) -> bool:
        """Разметка страницы, а не простая строка."""
        return self.fmt == "markdown"

    @property
    def needs_review(self) -> bool:
        """Результат нельзя считать полным: страница распознана не до конца."""
        return self.recognized and self.truncated


def analyze_layout(markdown: str) -> Layout:
    """Считает структуру страницы по Markdown: заголовки, строки таблиц, строки текста."""
    headings = 0
    table_rows = 0
    lines = 0

    for raw in markdown.splitlines():
        line = raw.strip()
        if not line:
            continue
        lines += 1
        if line.startswith("#"):
            headings += 1
        elif line.startswith("|"):
            # Строка-разделитель таблицы («|---|---|») — это разметка, а не данные.
            if set(line) <= set("|-: "):
                continue
            table_rows += 1

    return Layout(headings=headings, table_rows=table_rows, lines=lines)


def extract_text(path: Path, mode: str = "auto") -> Recognized:
    """Читает файл с диска, кодирует в Base64, отправляет на распознавание."""
    data = path.read_bytes()

    fmt = image_format(data)
    if fmt == "PDF":
        raise ValueError(
            "Это PDF. Сервис распознаёт страницу-ИЗОБРАЖЕНИЕ — отрендерите PDF "
            "в картинки и отправьте их постранично."
        )
    if fmt is None:
        raise ValueError(
            "Не похоже на изображение: поддерживаются PNG, JPEG, GIF, BMP, WEBP, TIFF."
        )
    if len(data) > MAX_IMAGE_BYTES:
        raise ValueError(
            f"Изображение больше 10 МБ ({len(data) / (1024 * 1024):.1f} МБ) — "
            "сервис такое не примет."
        )

    result = image_to_text(data, mode=mode)

    recognized = bool(result.get("recognized"))
    text = result.get("text")
    # Неизвестный формат трактуем как "plain" — так предписывает контракт.
    response_format = result.get("format") if result.get("format") == "markdown" else "plain"

    return Recognized(
        recognized=recognized,
        text=text,
        fmt=response_format,
        truncated=bool(result.get("truncated")),
        # elapsedMs приходит числом, но по спеке допустима и строка (int64).
        elapsed_ms=int(result.get("elapsedMs") or 0),
        mode=str(result.get("mode") or "auto"),
        units=int(result.get("units") or 0),
        source=path,
        size_bytes=len(data),
        image_format=fmt,
        layout=analyze_layout(text) if recognized and text and response_format == "markdown" else None,
    )


def print_text(text: str) -> None:
    """Печатает распознанный текст, ограничивая вывод разумным числом строк."""
    lines = text.splitlines()
    print("--- начало распознанного текста ---")
    for line in lines[:PREVIEW_LINES]:
        print(line)
    if len(lines) > PREVIEW_LINES:
        print(f"... ещё {len(lines) - PREVIEW_LINES} строк(и) — полностью лежат в поле text")
    print("--- конец распознанного текста ---")


def main() -> int:
    # --mode <значение>; без него запрос уходит в режиме "auto".
    raw_args = sys.argv[1:]
    mode = "auto"
    if "--mode" in raw_args:
        index = raw_args.index("--mode")
        if index + 1 >= len(raw_args):
            print("После --mode нужно указать режим.", file=sys.stderr)
            return 1
        mode = raw_args[index + 1]
        raw_args = raw_args[:index] + raw_args[index + 2:]
    args = raw_args

    path = Path(args[0]) if args else DEFAULT_IMAGE

    if API_KEY == SANDBOX_KEY:
        print(
            "Демо-ключ: сервис ВЕРНЁТ СГЕНЕРИРОВАННУЮ СТРАНИЦУ (мок), а не результат\n"
            "настоящего распознавания вашего изображения. Контракт, разметка и формат\n"
            "ответа — настоящие; качество распознавания проверяется боевым ключом.\n"
        )

    try:
        result = extract_text(path, mode=mode)
    except FileNotFoundError:
        print(f"Файл не найден: {path}", file=sys.stderr)
        return 1
    except ValueError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1
    except AtloriumError as error:
        print(f"Ошибка: {error}", file=sys.stderr)
        return 1

    print(f"Файл: {result.source.name} · {result.image_format} · {result.size_bytes} байт")

    if not result.recognized:
        print(f"Время обработки: {result.elapsed_ms} мс")
        print(f"Режим: {result.mode}, единиц работы: {result.units}")
        print("\nВердикт: читаемого текста на изображении не найдено — плата НЕ взимается.")
        print("Попробуйте поднять разрешение (150-300 dpi), увеличить контраст,")
        print("выровнять страницу или обрезать поля.")
        return 0

    if result.is_markdown:
        layout = result.layout
        print("Формат ответа: markdown — содержимое страницы с разметкой")
        if layout is not None:
            print(
                f"Структура: заголовков - {layout.headings}, "
                f"строк таблиц - {layout.table_rows}, строк текста - {layout.lines}"
            )
    else:
        print("Формат ответа: plain — простая строка без разметки")

    print(f"Время обработки: {result.elapsed_ms} мс")
    print(f"Режим: {result.mode}, единиц работы: {result.units}\n")
    print_text(result.text or "")

    if result.needs_review:
        print("\nВердикт: страница распознана НЕ ПОЛНОСТЬЮ — ответ оборван по длине.")
        print("Отправьте страницу на ручную проверку: часть содержимого в текст не попала.")
        print("Использовать такой результат как полный нельзя.")
    else:
        print("\nВердикт: страница распознана полностью — запрос тарифицируется.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
