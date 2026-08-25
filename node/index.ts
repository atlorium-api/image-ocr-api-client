/**
 * Клиент API распознавания текста с изображения (OCR) Atlorium —
 * страница документа → Markdown.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   npm install
 *   npm start
 *   npm start -- ../sample.png --mode digits
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import { readFile } from 'node:fs/promises';
import { basename, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';

/**
 * Публичный демо-ключ. С ним API отвечает МОКОМ: возвращается сгенерированная
 * страница документа, а НЕ результат настоящего распознавания вашей картинки.
 * Ответ детерминирован (seed берётся из самой картинки), поэтому на нём можно
 * писать стабильные тесты — но качество распознавания он не показывает.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

const API_KEY = process.env.ATLORIUM_API_KEY ?? SANDBOX_KEY;
const BASE_URL = process.env.ATLORIUM_BASE_URL ?? 'https://atlorium.com';

// Распознавание синхронное: ответ приходит в том же HTTP-вызове, и на плотной
// странице это занимает секунды. Таймаут с запасом.
const TIMEOUT_MS = 120_000;

const RETRY_DELAY_SEC = 20;
const MAX_RETRIES = 1;

/**
 * Потолок ожидания при 429. Исчерпав часовое окно, сервер честно просит подождать
 * десятки минут — клиент, слепо доверяющий Retry-After, зависнет на всё это время
 * (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
 */
const MAX_RETRY_DELAY_SEC = 120;

/** Образец лежит в КОРНЕ репозитория — один на все шесть примеров. */
const DEFAULT_IMAGE = resolve(fileURLToPath(new URL('.', import.meta.url)), '..', 'sample.png');

/** Верхняя граница размера изображения на стороне сервиса — 10 МБ в декодированном виде. */
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

/** Сколько строк распознанного текста печатать: страница документа длиннее экрана. */
const PREVIEW_LINES = 40;

/** Тело запроса POST /api/Ocr/image-to-text. */
export interface ImageOcrRequest {
  /** Изображение страницы в Base64: «голый» base64 или data-URL "data:image/png;base64,...". */
  image: string;
  /**
   * Режим распознавания:
   *   "auto"     — сервис сам различает короткую строку и страницу;
   *   "document" — страница целиком, с сохранением структуры;
   *   "table"    — только таблица;
   *   "formula"  — формула, ответ в разметке LaTeX;
   *   "line"     — короткая строка из букв и цифр;
   *   "digits"   — короткая строка из одних цифр.
   * Неизвестное значение сервис отвергает кодом 400, а не подменяет молча:
   * распознать не в том режиме — значит выставить счёт за работу, которую не просили.
   */
  mode: string;
}

/** Ответ ImageOcrResponse. */
export interface ImageOcrResponse {
  /** true — содержимое страницы распознано и лежит в text; false — плата не взимается. */
  recognized: boolean;
  text: string | null;
  /** "markdown" — разметка страницы; "plain" — простая строка; "latex" — формула. Неизвестное значение = "plain". */
  format: string;
  /** true — ответ оборван по длине: страница распознана НЕ полностью. */
  truncated: boolean;
  /** Режим, в котором изображение распознано НА САМОМ ДЕЛЕ (для "auto" — решение сервиса). */
  mode: string;
  /** Единицы работы, в которые обошёлся запрос: по ним он и тарифицирован. */
  units: number;
  /** Время обработки, мс. По спеке допустимы и число, и строка (int64). */
  elapsedMs: number | string;
}

const ERROR_REASONS: Record<number, string> = {
  400: 'Изображение не передано, битый Base64 или размер больше 10 МБ (запрос НЕ тарифицируется)',
  401: 'API-ключ отсутствует, просрочен или недействителен',
  402: 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
  429: 'Превышен лимит запросов — повторите позже',
  503: 'Сервис распознавания временно недоступен (за сбой на своей стороне мы не списываем деньги)',
};

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
export class AtloriumError extends Error {
  constructor(readonly status: number, body: string) {
    const reason = ERROR_REASONS[status] ?? 'Неизвестная ошибка';
    super(`HTTP ${status}: ${reason}. Ответ сервера: ${body.slice(0, 200)}`);
    this.name = 'AtloriumError';
  }
}

/**
 * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
 * 0 означало бы busy-loop, десятки минут — зависание. 0 на выходе = «ждать бессмысленно».
 */
function retryAfter(response: Response): number {
  const seconds = Number.parseInt(response.headers.get('Retry-After') ?? '', 10);
  if (!Number.isFinite(seconds) || seconds <= 0) return RETRY_DELAY_SEC;
  return seconds <= MAX_RETRY_DELAY_SEC ? seconds : 0;
}

export type ImageKind = 'PNG' | 'JPEG' | 'GIF' | 'BMP' | 'WEBP' | 'TIFF';

/**
 * Формат по сигнатуре файла. Сервис принимает ТОЛЬКО изображения; PDF вынесен в
 * отдельную ветку, потому что его присылают чаще всего и ошибка должна быть внятной.
 */
export function imageFormat(data: Buffer): ImageKind | 'PDF' | null {
  const starts = (...bytes: number[]): boolean =>
    data.length >= bytes.length && bytes.every((byte, index) => data[index] === byte);

  if (data.subarray(0, 4).toString('ascii') === '%PDF') return 'PDF';
  if (starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) return 'PNG';
  if (starts(0xff, 0xd8, 0xff)) return 'JPEG';
  if (data.subarray(0, 4).toString('ascii') === 'GIF8') return 'GIF';
  if (data.subarray(0, 2).toString('ascii') === 'BM') return 'BMP';
  if (data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP') {
    return 'WEBP';
  }
  if (starts(0x49, 0x49, 0x2a, 0x00) || starts(0x4d, 0x4d, 0x00, 0x2a)) return 'TIFF';
  return null;
}

/** POST /api/Ocr/image-to-text — единственный эндпоинт сервиса. */
export async function imageToText(image: Buffer, mode = 'auto'): Promise<ImageOcrResponse> {
  const payload: ImageOcrRequest = { image: image.toString('base64'), mode };

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    const response = await fetch(new URL('/api/Ocr/image-to-text', BASE_URL), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(payload),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });

    // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
    if (response.status === 429 && attempt < MAX_RETRIES) {
      const delay = retryAfter(response);
      if (delay === 0) throw new AtloriumError(429, 'лимит по IP исчерпан, повторите позже');
      console.error(`  ... лимит запросов, пауза ${delay} с`);
      await sleep(delay * 1000);
      continue;
    }

    if (!response.ok) throw new AtloriumError(response.status, await response.text());
    return (await response.json()) as ImageOcrResponse;
  }

  throw new AtloriumError(429, 'лимит запросов не отпустил после повтора');
}

// ── Применение данных: разбор распознанной страницы ───────────────────────────
// Ответ сам по себе — просто JSON. Ценность появляется, когда по нему принимают
// решение. Здесь решение принимается по трём полям сразу:
//
//   recognized — читаемый текст найден. Плата берётся ТОЛЬКО за recognized=true;
//                recognized=false — деньги не списаны, изображение можно улучшить
//                и отправить снова, ничего не заплатив.
//   format     — чем является text: "markdown" (страница с заголовками, абзацами,
//                таблицами и формулами), "plain" (простая строка, режимы line/digits)
//                или "latex" (формула). Список значений может пополниться, поэтому
//                НЕизвестное значение разбираем как "plain" — так советует сам контракт.
//   truncated  — ответ оборван по длине, страница распознана НЕ ПОЛНОСТЬЮ. Это
//                самое коварное поле: обрезанный текст выглядит совершенно
//                нормальным, и без явной проверки потеря части документа пройдёт
//                незамеченной. Поэтому она поднимается до вердикта «требуется
//                ручная проверка», а не прячется в лог.

/** Структура распознанной страницы — считается по Markdown-разметке ответа. */
export interface Layout {
  headings: number;
  tableRows: number;
  lines: number;
}

export interface Recognized {
  recognized: boolean;
  text: string | null;
  /** Формат поля text: 'markdown' или 'plain'. */
  format: 'markdown' | 'plain';
  truncated: boolean;
  /** Режим, в котором изображение распознано на самом деле. */
  mode: string;
  /** Единицы работы, в которые обошёлся запрос, — по ним он и тарифицирован. */
  units: number;
  elapsedMs: number;
  source: string;
  sizeBytes: number;
  imageFormat: ImageKind;
  layout: Layout | null;
  /** Тарифицируется ли запрос. Нераспознанное изображение — бесплатно. */
  charged: boolean;
  /** Результат нельзя считать полным: страница распознана не до конца. */
  needsReview: boolean;
}

/** Считает структуру страницы по Markdown: заголовки, строки таблиц, строки текста. */
export function analyzeLayout(markdown: string): Layout {
  let headings = 0;
  let tableRows = 0;
  let lines = 0;

  for (const raw of markdown.split('\n')) {
    const line = raw.trim();
    if (line === '') continue;
    lines++;
    if (line.startsWith('#')) {
      headings++;
    } else if (line.startsWith('|')) {
      // Строка-разделитель таблицы («|---|---|») — это разметка, а не данные.
      if (/^[|\-: ]+$/.test(line)) continue;
      tableRows++;
    }
  }

  return { headings, tableRows, lines };
}

export async function extractText(path: string, mode = 'auto'): Promise<Recognized> {
  const data = await readFile(path);

  const kind = imageFormat(data);
  if (kind === 'PDF') {
    throw new Error(
      'Это PDF. Сервис распознаёт страницу-ИЗОБРАЖЕНИЕ — отрендерите PDF в картинки ' +
        'и отправьте их постранично.',
    );
  }
  if (kind === null) {
    throw new Error('Не похоже на изображение: поддерживаются PNG, JPEG, GIF, BMP, WEBP, TIFF.');
  }
  if (data.length > MAX_IMAGE_BYTES) {
    throw new Error(
      `Изображение больше 10 МБ (${(data.length / (1024 * 1024)).toFixed(1)} МБ) — сервис такое не примет.`,
    );
  }

  const result = await imageToText(data, mode);

  // recognized — ключевое поле контракта: читаемый текст найден и лежит в text.
  const recognized = result.recognized === true;
  const format = result.format === 'markdown' ? 'markdown' : 'plain';
  const truncated = result.truncated === true;

  return {
    recognized,
    text: result.text,
    format,
    truncated,
    mode: result.mode ?? 'auto',
    units: Number(result.units ?? 0),
    elapsedMs: Number(result.elapsedMs ?? 0),
    source: path,
    sizeBytes: data.length,
    imageFormat: kind,
    layout: recognized && result.text && format === 'markdown' ? analyzeLayout(result.text) : null,
    charged: recognized,
    needsReview: recognized && truncated,
  };
}

/** Печатает распознанный текст, ограничивая вывод разумным числом строк. */
function printText(text: string): void {
  const lines = text.replace(/[\r\n]+$/, '').split('\n');
  console.log('--- начало распознанного текста ---');
  for (const line of lines.slice(0, PREVIEW_LINES)) console.log(line);
  if (lines.length > PREVIEW_LINES) {
    console.log(`... ещё ${lines.length - PREVIEW_LINES} строк(и) — полностью лежат в поле text`);
  }
  console.log('--- конец распознанного текста ---');
}

async function main(): Promise<void> {
  // --mode <значение>; без него запрос уходит в режиме "auto".
  const args = process.argv.slice(2);
  let mode = 'auto';
  const modeIndex = args.indexOf('--mode');
  if (modeIndex !== -1) {
    const value = args[modeIndex + 1];
    if (value === undefined) {
      console.error('После --mode нужно указать режим.');
      process.exit(1);
    }
    mode = value;
    args.splice(modeIndex, 2);
  }
  const path = args[0] ?? DEFAULT_IMAGE;

  if (API_KEY === SANDBOX_KEY) {
    console.log(
      'Демо-ключ: сервис ВЕРНЁТ СГЕНЕРИРОВАННУЮ СТРАНИЦУ (мок), а не результат\n' +
        'настоящего распознавания вашего изображения. Контракт, разметка и формат\n' +
        'ответа — настоящие; качество распознавания проверяется боевым ключом.\n',
    );
  }

  const result = await extractText(path, mode);

  console.log(`Файл: ${basename(result.source)} · ${result.imageFormat} · ${result.sizeBytes} байт`);

  if (!result.recognized) {
    console.log(`Время обработки: ${result.elapsedMs} мс`);
    console.log(`Режим: ${result.mode}, единиц работы: ${result.units}`);
    console.log('\nВердикт: читаемого текста на изображении не найдено — плата НЕ взимается.');
    console.log('Попробуйте поднять разрешение (150-300 dpi), увеличить контраст,');
    console.log('выровнять страницу или обрезать поля.');
    return;
  }

  if (result.format === 'markdown') {
    console.log('Формат ответа: markdown — содержимое страницы с разметкой');
    if (result.layout) {
      console.log(
        `Структура: заголовков - ${result.layout.headings}, ` +
          `строк таблиц - ${result.layout.tableRows}, строк текста - ${result.layout.lines}`,
      );
    }
  } else {
    console.log('Формат ответа: plain — простая строка без разметки');
  }

  console.log(`Время обработки: ${result.elapsedMs} мс`);
  console.log(`Режим: ${result.mode}, единиц работы: ${result.units}\n`);
  printText(result.text ?? '');

  if (result.needsReview) {
    console.log('\nВердикт: страница распознана НЕ ПОЛНОСТЬЮ — ответ оборван по длине.');
    console.log('Отправьте страницу на ручную проверку: часть содержимого в текст не попала.');
    console.log('Использовать такой результат как полный нельзя.');
  } else {
    console.log('\nВердикт: страница распознана полностью — запрос тарифицируется.');
  }
}

// Запуск только когда файл выполняется напрямую, а не импортируется.
if (process.argv[1]?.includes('index')) {
  main().catch((error: unknown) => {
    console.error('Ошибка:', error instanceof Error ? error.message : error);
    process.exit(1);
  });
}
