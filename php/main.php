<?php

/**
 * Клиент API распознавания текста с изображения (OCR) Atlorium —
 * страница документа → Markdown.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе):
 *   php main.php
 *   php main.php ../sample.png --mode digits
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

declare(strict_types=1);

/**
 * Публичный демо-ключ. С ним API отвечает МОКОМ: возвращается сгенерированная
 * страница документа, а НЕ результат настоящего распознавания вашей картинки.
 * Ответ детерминирован (seed берётся из самой картинки) — на нём можно писать
 * стабильные тесты, но качество распознавания он не показывает.
 */
const SANDBOX_KEY = 'ak_sandbox_demo_mockdata_v1';

/** Распознавание синхронное и на плотной странице занимает секунды — таймаут с запасом. */
const TIMEOUT = 120;

const RETRY_DELAY = 20;
const MAX_RETRIES = 1;

/**
 * Потолок ожидания при 429. Исчерпав часовое окно, сервер честно просит подождать
 * десятки минут — клиент, слепо доверяющий Retry-After, зависнет на всё это время
 * (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
 */
const MAX_RETRY_DELAY = 120;

/** Верхняя граница размера изображения на стороне сервиса — 10 МБ в декодированном виде. */
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

/** Сколько строк распознанного текста печатать: страница документа длиннее экрана. */
const PREVIEW_LINES = 40;

/** Значения поля format ответа. */
const FORMAT_MARKDOWN = 'markdown';
const FORMAT_PLAIN = 'plain';

/** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
final class AtloriumError extends RuntimeException
{
    private const REASONS = [
        400 => 'Изображение не передано, битый Base64 или размер больше 10 МБ (запрос НЕ тарифицируется)',
        401 => 'API-ключ отсутствует, просрочен или недействителен',
        402 => 'Недостаточно кредитов на балансе — пополните на https://atlorium.com',
        429 => 'Превышен лимит запросов — повторите позже',
        503 => 'Сервис распознавания временно недоступен (за сбой на своей стороне мы не списываем деньги)',
    ];

    public function __construct(public readonly int $status, string $body)
    {
        $reason = self::REASONS[$status] ?? 'Неизвестная ошибка';
        parent::__construct(sprintf('HTTP %d: %s. Ответ сервера: %s', $status, $reason, mb_substr($body, 0, 200)));
    }
}

final class OcrClient
{
    private string $apiKey;
    private string $baseUrl;

    public function __construct(?string $apiKey = null, ?string $baseUrl = null)
    {
        $this->apiKey = $apiKey ?? (getenv('ATLORIUM_API_KEY') ?: SANDBOX_KEY);
        $this->baseUrl = $baseUrl ?? (getenv('ATLORIUM_BASE_URL') ?: 'https://atlorium.com');
    }

    public function isSandbox(): bool
    {
        return $this->apiKey === SANDBOX_KEY;
    }

    /**
     * POST /api/Ocr/image-to-text — единственный эндпоинт сервиса.
     *
     * Тело запроса (ImageOcrRequest):
     *   image — изображение страницы в Base64 («голый» base64 или data-URL);
     *   mode  — режим распознавания: "auto" (сервис сам различает строку и страницу),
     *           "document" (страница целиком), "table" (только таблица),
     *           "formula" (формула в LaTeX), "line" (строка из букв и цифр),
     *           "digits" (строка из одних цифр). Неизвестное значение сервис
     *           отвергает кодом 400, а не подменяет молча.
     *
     * @return array{recognized?: bool, text: ?string, format?: string, truncated?: bool,
     *               mode?: string, units?: int, elapsedMs: int|string}
     */
    public function imageToText(string $image, string $mode = 'auto'): array
    {
        $payload = ['image' => base64_encode($image), 'mode' => $mode];
        $body = json_encode($payload, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);

        for ($attempt = 0; $attempt <= MAX_RETRIES; $attempt++) {
            [$status, $responseBody, $headers] = $this->post('/api/Ocr/image-to-text', $body);

            // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
            if ($status === 429 && $attempt < MAX_RETRIES) {
                $delay = self::retryAfter($headers);
                if ($delay === 0) {
                    throw new AtloriumError(429, 'лимит по IP исчерпан, повторите позже');
                }
                fwrite(STDERR, "  ... лимит запросов, пауза {$delay} с\n");
                sleep($delay);
                continue;
            }

            if ($status !== 200) {
                throw new AtloriumError($status, $responseBody);
            }

            return json_decode($responseBody, true, 512, JSON_THROW_ON_ERROR);
        }

        throw new AtloriumError(429, 'лимит запросов не отпустил после повтора');
    }

    /**
     * @return array{0: int, 1: string, 2: array<string, string>}
     */
    private function post(string $path, string $body): array
    {
        $headers = [];

        $curl = curl_init($this->baseUrl . $path);
        curl_setopt_array($curl, [
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $body,
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT => TIMEOUT,
            CURLOPT_HTTPHEADER => [
                'Authorization: Bearer ' . $this->apiKey,
                'Content-Type: application/json',
                'Accept: application/json',
            ],
            CURLOPT_HEADERFUNCTION => static function ($curl, string $header) use (&$headers): int {
                $parts = explode(':', $header, 2);
                if (count($parts) === 2) {
                    $headers[strtolower(trim($parts[0]))] = trim($parts[1]);
                }
                return strlen($header);
            },
        ]);

        $responseBody = curl_exec($curl);
        if ($responseBody === false) {
            $error = curl_error($curl);
            curl_close($curl);
            throw new RuntimeException("Сетевая ошибка: {$error}");
        }

        $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
        curl_close($curl);

        return [(int) $status, (string) $responseBody, $headers];
    }

    /**
     * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
     * 0 означал бы busy-loop, десятки минут — зависание. 0 на выходе = «ждать бессмысленно».
     *
     * @param array<string, string> $headers
     */
    private static function retryAfter(array $headers): int
    {
        $seconds = (int) ($headers['retry-after'] ?? 0);
        if ($seconds <= 0) {
            return RETRY_DELAY;
        }

        return $seconds <= MAX_RETRY_DELAY ? $seconds : 0;
    }
}

/**
 * Формат по сигнатуре файла. Сервис принимает ТОЛЬКО изображения; PDF вынесен в
 * отдельную ветку — его присылают чаще всего, и ошибка должна быть внятной.
 *
 * Имя функции нарочно не совпадает со встроенными PHP-функциями: имена функций в
 * PHP регистронезависимы, и столкновение со встроенной роняет весь файл на разборе.
 */
function detectImageFormat(string $data): ?string
{
    if (str_starts_with($data, '%PDF')) {
        return 'PDF';
    }
    if (str_starts_with($data, "\x89PNG\r\n\x1a\n")) {
        return 'PNG';
    }
    if (str_starts_with($data, "\xff\xd8\xff")) {
        return 'JPEG';
    }
    if (str_starts_with($data, 'GIF8')) {
        return 'GIF';
    }
    if (str_starts_with($data, 'BM')) {
        return 'BMP';
    }
    if (str_starts_with($data, 'RIFF') && strlen($data) >= 12 && substr($data, 8, 4) === 'WEBP') {
        return 'WEBP';
    }
    if (str_starts_with($data, "II*\x00") || str_starts_with($data, "MM\x00*")) {
        return 'TIFF';
    }

    return null;
}

/**
 * Считает структуру страницы по Markdown: заголовки, строки таблиц, строки текста.
 *
 * @return array{headings: int, tableRows: int, lines: int}
 */
function analyzeLayout(string $markdown): array
{
    $headings = 0;
    $tableRows = 0;
    $lines = 0;

    foreach (explode("\n", $markdown) as $raw) {
        $line = trim($raw);
        if ($line === '') {
            continue;
        }
        $lines++;
        if (str_starts_with($line, '#')) {
            $headings++;
        } elseif (str_starts_with($line, '|')) {
            // Строка-разделитель таблицы («|---|---|») — это разметка, а не данные.
            if (preg_match('/^[|\-: ]+$/', $line) === 1) {
                continue;
            }
            $tableRows++;
        }
    }

    return ['headings' => $headings, 'tableRows' => $tableRows, 'lines' => $lines];
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

/**
 * @return array{recognized: bool, text: ?string, format: string, truncated: bool,
 *               mode: string, units: int, elapsedMs: int, source: string, sizeBytes: int,
 *               imageFormat: string, layout: ?array{headings: int, tableRows: int, lines: int},
 *               charged: bool, needsReview: bool}
 */
function extractText(OcrClient $client, string $path, string $mode = 'auto'): array
{
    $data = @file_get_contents($path);
    if ($data === false) {
        throw new RuntimeException("Файл не найден: {$path}");
    }

    $kind = detectImageFormat($data);
    if ($kind === 'PDF') {
        throw new RuntimeException(
            'Это PDF. Сервис распознаёт страницу-ИЗОБРАЖЕНИЕ — '
            . 'отрендерите PDF в картинки и отправьте их постранично.'
        );
    }
    if ($kind === null) {
        throw new RuntimeException('Не похоже на изображение: поддерживаются PNG, JPEG, GIF, BMP, WEBP, TIFF.');
    }
    if (strlen($data) > MAX_IMAGE_BYTES) {
        throw new RuntimeException(
            'Изображение больше 10 МБ (' . number_format(strlen($data) / (1024 * 1024), 1, '.', '')
            . ' МБ) — сервис такое не примет.'
        );
    }

    $result = $client->imageToText($data, $mode);

    // recognized — ключевое поле контракта: читаемый текст найден и лежит в text.
    $recognized = (bool) ($result['recognized'] ?? false);
    $text = $result['text'] ?? null;
    $format = (($result['format'] ?? '') === FORMAT_MARKDOWN) ? FORMAT_MARKDOWN : FORMAT_PLAIN;
    $truncated = (bool) ($result['truncated'] ?? false);
    // Режим ответа сервер всегда заполняет; на всякий случай подстрахуемся значением "auto".
    $responseMode = (string) ($result['mode'] ?? '');
    if ($responseMode === '') {
        $responseMode = 'auto';
    }

    return [
        'recognized' => $recognized,
        'text' => $text,
        'format' => $format,
        'truncated' => $truncated,
        'mode' => $responseMode,
        'units' => (int) ($result['units'] ?? 0),
        'elapsedMs' => (int) ($result['elapsedMs'] ?? 0),
        'source' => $path,
        'sizeBytes' => strlen($data),
        'imageFormat' => $kind,
        'layout' => ($recognized && is_string($text) && $text !== '' && $format === FORMAT_MARKDOWN)
            ? analyzeLayout($text)
            : null,
        // Тарифицируется ли запрос. Нераспознанное изображение — бесплатно.
        'charged' => $recognized,
        // Результат нельзя считать полным: страница распознана не до конца.
        'needsReview' => $recognized && $truncated,
    ];
}

/** Печатает распознанный текст, ограничивая вывод разумным числом строк. */
function printRecognizedText(string $text): void
{
    $lines = explode("\n", rtrim($text, "\r\n"));
    echo "--- начало распознанного текста ---\n";
    foreach (array_slice($lines, 0, PREVIEW_LINES) as $line) {
        echo $line . "\n";
    }
    if (count($lines) > PREVIEW_LINES) {
        echo '... ещё ' . (count($lines) - PREVIEW_LINES) . " строк(и) — полностью лежат в поле text\n";
    }
    echo "--- конец распознанного текста ---\n";
}

// ── Демонстрация ─────────────────────────────────────────────────────────────

$client = new OcrClient();

// Образец лежит в КОРНЕ репозитория — один на все шесть примеров.
$path = __DIR__ . DIRECTORY_SEPARATOR . '..' . DIRECTORY_SEPARATOR . 'sample.png';
// --mode <значение>; без него запрос уходит в режиме "auto".
$mode = 'auto';

$cliArgs = array_slice($argv, 1);
for ($i = 0; $i < count($cliArgs); $i++) {
    if ($cliArgs[$i] === '--mode') {
        if (!isset($cliArgs[$i + 1])) {
            fwrite(STDERR, "После --mode нужно указать режим.\n");
            exit(1);
        }
        $mode = $cliArgs[$i + 1];
        $i++;
    } else {
        $path = $cliArgs[$i];
    }
}

if ($client->isSandbox()) {
    echo "Демо-ключ: сервис ВЕРНЁТ СГЕНЕРИРОВАННУЮ СТРАНИЦУ (мок), а не результат\n";
    echo "настоящего распознавания вашего изображения. Контракт, разметка и формат\n";
    echo "ответа — настоящие; качество распознавания проверяется боевым ключом.\n\n";
}

try {
    $result = extractText($client, $path, $mode);
} catch (RuntimeException $error) { // AtloriumError — наследник RuntimeException
    fwrite(STDERR, "Ошибка: {$error->getMessage()}\n");
    exit(1);
}

echo 'Файл: ' . basename($result['source']) . " · {$result['imageFormat']} · {$result['sizeBytes']} байт\n";

if (!$result['recognized']) {
    echo "Время обработки: {$result['elapsedMs']} мс\n";
    echo "Режим: {$result['mode']}, единиц работы: {$result['units']}\n";
    echo "\nВердикт: читаемого текста на изображении не найдено — плата НЕ взимается.\n";
    echo "Попробуйте поднять разрешение (150-300 dpi), увеличить контраст,\n";
    echo "выровнять страницу или обрезать поля.\n";
    exit(0);
}

if ($result['format'] === FORMAT_MARKDOWN) {
    echo "Формат ответа: markdown — содержимое страницы с разметкой\n";
    if ($result['layout'] !== null) {
        echo "Структура: заголовков - {$result['layout']['headings']}, "
            . "строк таблиц - {$result['layout']['tableRows']}, "
            . "строк текста - {$result['layout']['lines']}\n";
    }
} else {
    echo "Формат ответа: plain — простая строка без разметки\n";
}

echo "Время обработки: {$result['elapsedMs']} мс\n";
echo "Режим: {$result['mode']}, единиц работы: {$result['units']}\n\n";
printRecognizedText((string) $result['text']);

if ($result['needsReview']) {
    echo "\nВердикт: страница распознана НЕ ПОЛНОСТЬЮ — ответ оборван по длине.\n";
    echo "Отправьте страницу на ручную проверку: часть содержимого в текст не попала.\n";
    echo "Использовать такой результат как полный нельзя.\n";
} else {
    echo "\nВердикт: страница распознана полностью — запрос тарифицируется.\n";
}
