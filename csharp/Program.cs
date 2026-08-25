// Клиент API распознавания текста с изображения (OCR) Atlorium —
// страница документа → Markdown.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//     dotnet run
//     dotnet run ../sample.png --mode digits
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.

using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

// Публичный демо-ключ. С ним API отвечает МОКОМ: возвращается сгенерированная
// страница документа, а НЕ результат настоящего распознавания вашей картинки.
// Ответ детерминирован (seed берётся из самой картинки) — на нём можно писать
// стабильные тесты, но качество распознавания он не показывает.
const string SandboxKey = "ak_sandbox_demo_mockdata_v1";

var apiKey = Environment.GetEnvironmentVariable("ATLORIUM_API_KEY") ?? SandboxKey;
var baseUrl = Environment.GetEnvironmentVariable("ATLORIUM_BASE_URL") ?? "https://atlorium.com";

using var http = new HttpClient
{
    BaseAddress = new Uri(baseUrl),
    // Распознавание синхронное и на плотной странице занимает секунды — таймаут с запасом.
    Timeout = TimeSpan.FromSeconds(120),
};
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", apiKey);
http.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));

var client = new OcrClient(http);

// Образец лежит в КОРНЕ репозитория — один на все шесть примеров.
// --mode <значение>; без него запрос уходит в режиме "auto".
var mode = "auto";
string? path = null;
for (var i = 0; i < args.Length; i++)
{
    if (args[i] == "--mode")
    {
        if (i + 1 >= args.Length)
        {
            Console.Error.WriteLine("После --mode нужно указать режим.");
            return 1;
        }
        mode = args[++i];
        continue;
    }
    path ??= args[i];
}
path ??= Path.Combine("..", "sample.png");

if (apiKey == SandboxKey)
{
    Console.WriteLine("Демо-ключ: сервис ВЕРНЁТ СГЕНЕРИРОВАННУЮ СТРАНИЦУ (мок), а не результат");
    Console.WriteLine("настоящего распознавания вашего изображения. Контракт, разметка и формат");
    Console.WriteLine("ответа — настоящие; качество распознавания проверяется боевым ключом.\n");
}

Recognized result;
try
{
    result = await client.ExtractTextAsync(path, mode);
}
catch (Exception error) when (error is AtloriumException or IOException or InvalidOperationException)
{
    Console.Error.WriteLine($"Ошибка: {error.Message}");
    return 1;
}

Console.WriteLine($"Файл: {Path.GetFileName(result.Source)} · {result.ImageFormat} · {result.SizeBytes} байт");

if (!result.IsRecognized)
{
    Console.WriteLine($"Время обработки: {result.ElapsedMs} мс");
    Console.WriteLine($"Режим: {result.Mode}, единиц работы: {result.Units}");
    Console.WriteLine("\nВердикт: читаемого текста на изображении не найдено — плата НЕ взимается.");
    Console.WriteLine("Попробуйте поднять разрешение (150-300 dpi), увеличить контраст,");
    Console.WriteLine("выровнять страницу или обрезать поля.");
    return 0;
}

if (result.Format == OcrFormats.Markdown)
{
    Console.WriteLine("Формат ответа: markdown — содержимое страницы с разметкой");
    if (result.Layout is { } layout)
    {
        Console.WriteLine($"Структура: заголовков - {layout.Headings}, "
                          + $"строк таблиц - {layout.TableRows}, строк текста - {layout.Lines}");
    }
}
else
{
    Console.WriteLine("Формат ответа: plain — простая строка без разметки");
}

Console.WriteLine($"Время обработки: {result.ElapsedMs} мс");
Console.WriteLine($"Режим: {result.Mode}, единиц работы: {result.Units}\n");
PrintText(result.Text ?? string.Empty);

if (result.NeedsReview)
{
    Console.WriteLine("\nВердикт: страница распознана НЕ ПОЛНОСТЬЮ — ответ оборван по длине.");
    Console.WriteLine("Отправьте страницу на ручную проверку: часть содержимого в текст не попала.");
    Console.WriteLine("Использовать такой результат как полный нельзя.");
}
else
{
    Console.WriteLine("\nВердикт: страница распознана полностью — запрос тарифицируется.");
}

return 0;

// Печатает распознанный текст, ограничивая вывод разумным числом строк.
static void PrintText(string text)
{
    const int previewLines = 40;
    var lines = text.TrimEnd('\r', '\n').Split('\n');

    Console.WriteLine("--- начало распознанного текста ---");
    foreach (var line in lines.Take(previewLines))
    {
        Console.WriteLine(line.TrimEnd('\r'));
    }
    if (lines.Length > previewLines)
    {
        Console.WriteLine($"... ещё {lines.Length - previewLines} строк(и) — полностью лежат в поле text");
    }
    Console.WriteLine("--- конец распознанного текста ---");
}

// ── Клиент ───────────────────────────────────────────────────────────────────

/// <summary>Значения поля <c>format</c> ответа.</summary>
public static class OcrFormats
{
    /// <summary>Содержимое страницы с разметкой Markdown.</summary>
    public const string Markdown = "markdown";

    /// <summary>Простая строка без разметки (режимы line/digits).</summary>
    public const string Plain = "plain";

    /// <summary>Формула в разметке LaTeX (режим formula).</summary>
    public const string Latex = "latex";
}

/// <summary>Ошибка API: HTTP-код разложен в человекочитаемую причину.</summary>
public sealed class AtloriumException(HttpStatusCode status, string body)
    : Exception($"HTTP {(int)status}: {Explain(status)}. Ответ сервера: {body[..Math.Min(200, body.Length)]}")
{
    public HttpStatusCode Status { get; } = status;

    private static string Explain(HttpStatusCode status) => (int)status switch
    {
        400 => "Изображение не передано, битый Base64 или размер больше 10 МБ (запрос НЕ тарифицируется)",
        401 => "API-ключ отсутствует, просрочен или недействителен",
        402 => "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
        429 => "Превышен лимит запросов — повторите позже",
        503 => "Сервис распознавания временно недоступен (за сбой на своей стороне мы не списываем деньги)",
        _ => "Неизвестная ошибка",
    };
}

public sealed class OcrClient(HttpClient http)
{
    private const int MaxRetries = 1;
    private const int RetryDelaySeconds = 20;

    // Потолок ожидания при 429. Исчерпав часовое окно, сервер честно просит подождать
    // десятки минут — клиент, слепо доверяющий Retry-After, зависнет на всё это время
    // (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
    private const int MaxRetryDelaySeconds = 120;

    // Верхняя граница размера изображения на стороне сервиса — 10 МБ в декодированном виде.
    private const int MaxImageBytes = 10 * 1024 * 1024;

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    /// <summary>
    /// POST /api/Ocr/image-to-text — единственный эндпоинт сервиса.
    /// </summary>
    /// <param name="image">Изображение страницы. Кодируется в Base64 (сервис принимает и data-URL).</param>
    /// <param name="mode">
    /// Режим распознавания: <c>"auto"</c> (сервис сам различает строку и страницу),
    /// <c>"document"</c> (страница целиком), <c>"table"</c> (только таблица),
    /// <c>"formula"</c> (формула в LaTeX), <c>"line"</c> (строка из букв и цифр),
    /// <c>"digits"</c> (строка из одних цифр). Неизвестное значение сервис отвергает
    /// кодом 400, а не подменяет молча.
    /// </param>
    public async Task<ImageOcrResponse> ImageToTextAsync(byte[] image, string mode = "auto")
    {
        var payload = new ImageOcrRequest(Convert.ToBase64String(image), mode);

        for (var attempt = 0; attempt <= MaxRetries; attempt++)
        {
            using var response = await http.PostAsJsonAsync("/api/Ocr/image-to-text", payload, JsonOptions);

            // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
            if (response.StatusCode == HttpStatusCode.TooManyRequests && attempt < MaxRetries)
            {
                var delay = RetryAfter(response);
                if (delay == 0)
                {
                    throw new AtloriumException(HttpStatusCode.TooManyRequests,
                        "лимит по IP исчерпан, повторите позже");
                }
                Console.Error.WriteLine($"  ... лимит запросов, пауза {delay} с");
                await Task.Delay(TimeSpan.FromSeconds(delay));
                continue;
            }

            if (!response.IsSuccessStatusCode)
            {
                throw new AtloriumException(response.StatusCode, await response.Content.ReadAsStringAsync());
            }

            return await response.Content.ReadFromJsonAsync<ImageOcrResponse>(JsonOptions)
                   ?? throw new InvalidOperationException("Пустой ответ API.");
        }

        throw new AtloriumException(HttpStatusCode.TooManyRequests, "лимит запросов не отпустил после повтора");
    }

    // ── Применение данных: разбор распознанной страницы ───────────────────────
    // Ответ сам по себе — просто JSON. Ценность появляется, когда по нему принимают
    // решение. Здесь решение принимается по трём полям сразу:
    //
    //   Recognized — читаемый текст найден. Плата берётся ТОЛЬКО за Recognized=true;
    //                Recognized=false — деньги не списаны, изображение можно улучшить
    //                и отправить снова, ничего не заплатив.
    //   Format     — чем является Text: "markdown" (страница с заголовками, абзацами,
    //                таблицами и формулами), "plain" (простая строка, режимы line/digits)
    //                или "latex" (формула). Список значений может пополниться, поэтому
    //                НЕизвестное значение разбираем как "plain" — так советует сам контракт.
    //   Truncated  — ответ оборван по длине, страница распознана НЕ ПОЛНОСТЬЮ. Это
    //                самое коварное поле: обрезанный текст выглядит совершенно
    //                нормальным, и без явной проверки потеря части документа пройдёт
    //                незамеченной. Поэтому она поднимается до вердикта «требуется
    //                ручная проверка», а не прячется в лог.

    /// <summary>
    /// Читает файл с диска, кодирует в Base64 и отправляет на распознавание.
    /// </summary>
    public async Task<Recognized> ExtractTextAsync(string path, string mode = "auto")
    {
        var data = await File.ReadAllBytesAsync(path);

        var kind = DetectFormat(data);
        if (kind == "PDF")
        {
            throw new InvalidOperationException(
                "Это PDF. Сервис распознаёт страницу-ИЗОБРАЖЕНИЕ — " +
                "отрендерите PDF в картинки и отправьте их постранично.");
        }
        if (kind is null)
        {
            throw new InvalidOperationException(
                "Не похоже на изображение: поддерживаются PNG, JPEG, GIF, BMP, WEBP, TIFF.");
        }
        if (data.Length > MaxImageBytes)
        {
            // Инвариантная культура: иначе на ru-RU машине получится «10,4», а на CI «10.4»,
            // и вывод примера разойдётся с README.
            var megabytes = (data.Length / (1024.0 * 1024.0)).ToString("0.0", CultureInfo.InvariantCulture);
            throw new InvalidOperationException(
                $"Изображение больше 10 МБ ({megabytes} МБ) — сервис такое не примет.");
        }

        var result = await ImageToTextAsync(data, mode);

        // Recognized — ключевое поле контракта: читаемый текст найден и лежит в Text.
        var format = result.Format == OcrFormats.Markdown ? OcrFormats.Markdown : OcrFormats.Plain;
        var layout = result.Recognized && !string.IsNullOrEmpty(result.Text) && format == OcrFormats.Markdown
            ? AnalyzeLayout(result.Text)
            : null;

        return new Recognized(
            result.Recognized,
            result.Text,
            format,
            result.Truncated,
            string.IsNullOrEmpty(result.Mode) ? "auto" : result.Mode,
            result.Units,
            result.ElapsedMs,
            path,
            data.Length,
            kind,
            layout);
    }

    /// <summary>
    /// Считает структуру страницы по Markdown: заголовки, строки таблиц, строки текста.
    /// </summary>
    public static Layout AnalyzeLayout(string markdown)
    {
        var headings = 0;
        var tableRows = 0;
        var lines = 0;

        foreach (var raw in markdown.Split('\n'))
        {
            var line = raw.Trim();
            if (line.Length == 0)
            {
                continue;
            }
            lines++;
            if (line.StartsWith('#'))
            {
                headings++;
            }
            else if (line.StartsWith('|'))
            {
                // Строка-разделитель таблицы («|---|---|») — это разметка, а не данные.
                if (line.All(c => c is '|' or '-' or ':' or ' '))
                {
                    continue;
                }
                tableRows++;
            }
        }

        return new Layout(headings, tableRows, lines);
    }

    /// <summary>
    /// Формат по сигнатуре файла. Сервис принимает ТОЛЬКО изображения; PDF вынесен в
    /// отдельную ветку — его присылают чаще всего, и ошибка должна быть внятной.
    /// </summary>
    private static string? DetectFormat(byte[] data)
    {
        if (StartsWith(data, "%PDF"u8)) return "PDF";
        if (StartsWith(data, [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])) return "PNG";
        if (StartsWith(data, [0xFF, 0xD8, 0xFF])) return "JPEG";
        if (StartsWith(data, "GIF8"u8)) return "GIF";
        if (StartsWith(data, "BM"u8)) return "BMP";
        if (StartsWith(data, "RIFF"u8) && data.Length >= 12 && data.AsSpan(8, 4).SequenceEqual("WEBP"u8)) return "WEBP";
        if (StartsWith(data, [0x49, 0x49, 0x2A, 0x00]) || StartsWith(data, [0x4D, 0x4D, 0x00, 0x2A])) return "TIFF";
        return null;
    }

    private static bool StartsWith(byte[] data, ReadOnlySpan<byte> signature) =>
        data.Length >= signature.Length && data.AsSpan(0, signature.Length).SequenceEqual(signature);

    /// <summary>
    /// Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
    /// 0 означал бы busy-loop, десятки минут — зависание. 0 на выходе = «ждать бессмысленно».
    /// </summary>
    private static int RetryAfter(HttpResponseMessage response)
    {
        var seconds = (int?)response.Headers.RetryAfter?.Delta?.TotalSeconds ?? 0;
        if (seconds <= 0)
        {
            return RetryDelaySeconds;
        }
        return seconds <= MaxRetryDelaySeconds ? seconds : 0;
    }
}

// ── Модель запроса и ответа ──────────────────────────────────────────────────

/// <summary>Тело POST /api/Ocr/image-to-text.</summary>
public sealed record ImageOcrRequest(
    [property: JsonPropertyName("image")] string Image,
    [property: JsonPropertyName("mode")] string Mode);

/// <summary>Ответ сервиса распознавания.</summary>
public sealed record ImageOcrResponse
{
    /// <summary>
    /// true — содержимое страницы распознано и лежит в <see cref="Text"/>;
    /// false — читаемого текста не найдено, плата не взимается.
    /// </summary>
    public bool Recognized { get; init; }

    /// <summary>Содержимое страницы; null, если распознать не удалось.</summary>
    public string? Text { get; init; }

    /// <summary>
    /// Формат поля <see cref="Text"/>: "markdown" (разметка страницы), "plain"
    /// (простая строка) или "latex" (формула). Неизвестное значение разбирается как "plain".
    /// </summary>
    public string Format { get; init; } = OcrFormats.Plain;

    /// <summary>true — ответ оборван по длине: страница распознана НЕ полностью.</summary>
    public bool Truncated { get; init; }

    /// <summary>
    /// Режим, в котором изображение распознано НА САМОМ ДЕЛЕ (для "auto" — решение сервиса).
    /// </summary>
    public string Mode { get; init; } = "auto";

    /// <summary>
    /// Единицы работы, в которые обошёлся запрос, — по ним он и тарифицирован.
    /// Короткая строка — всегда 1, страница — столько, сколько на ней распознано областей.
    /// </summary>
    public int Units { get; init; }

    /// <summary>Время обработки, мс. По спеке допустимы и число, и строка (int64).</summary>
    [JsonNumberHandling(JsonNumberHandling.AllowReadingFromString)]
    public long ElapsedMs { get; init; }
}

/// <summary>Структура распознанной страницы — считается по Markdown-разметке ответа.</summary>
public sealed record Layout(int Headings, int TableRows, int Lines);

public sealed record Recognized(
    bool IsRecognized,
    string? Text,
    string Format,
    bool Truncated,
    string Mode,
    int Units,
    long ElapsedMs,
    string Source,
    int SizeBytes,
    string ImageFormat,
    Layout? Layout)
{
    /// <summary>Тарифицируется ли запрос. Плата берётся только за успешное распознавание.</summary>
    public bool Charged => IsRecognized;

    /// <summary>Результат нельзя считать полным: страница распознана не до конца.</summary>
    public bool NeedsReview => IsRecognized && Truncated;
}
