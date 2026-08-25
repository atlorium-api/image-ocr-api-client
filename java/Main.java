/*
 * Клиент API распознавания текста с изображения (OCR) Atlorium —
 * страница документа → Markdown.
 *
 * Запуск (работает сразу, без регистрации — на демо-ключе).
 * Файл запускается напрямую, без компиляции и без зависимостей:
 *
 *     java Main.java
 *     java Main.java ../sample.png --mode digits
 *
 * Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
 * ATLORIUM_API_KEY. Код при этом не меняется.
 */

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    /**
     * Публичный демо-ключ. С ним API отвечает МОКОМ: возвращается сгенерированная
     * страница документа, а НЕ результат настоящего распознавания вашей картинки.
     * Ответ детерминирован (seed берётся из самой картинки) — на нём можно писать
     * стабильные тесты, но качество распознавания он не показывает.
     */
    static final String SANDBOX_KEY = "ak_sandbox_demo_mockdata_v1";

    static final String API_KEY = envOr("ATLORIUM_API_KEY", SANDBOX_KEY);
    static final String BASE_URL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com");

    /** Распознавание синхронное и на плотной странице занимает секунды — таймаут с запасом. */
    static final Duration TIMEOUT = Duration.ofSeconds(120);

    static final int RETRY_DELAY_SEC = 20;
    static final int MAX_RETRIES = 1;

    /**
     * Потолок ожидания при 429. Исчерпав часовое окно, сервер честно просит подождать
     * десятки минут — клиент, слепо доверяющий Retry-After, зависнет на всё это время
     * (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
     */
    static final int MAX_RETRY_DELAY_SEC = 120;

    /** Верхняя граница размера изображения на стороне сервиса — 10 МБ в декодированном виде. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    /** Сколько строк распознанного текста печатать: страница документа длиннее экрана. */
    static final int PREVIEW_LINES = 40;

    /** Значения поля format ответа. */
    static final String FORMAT_MARKDOWN = "markdown";
    static final String FORMAT_PLAIN = "plain";

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    static String envOr(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Ошибка API: HTTP-код разложен в человекочитаемую причину. */
    static class AtloriumException extends RuntimeException {
        private static final Map<Integer, String> REASONS = Map.of(
                400, "Изображение не передано, битый Base64 или размер больше 10 МБ (запрос НЕ тарифицируется)",
                401, "API-ключ отсутствует, просрочен или недействителен",
                402, "Недостаточно кредитов на балансе — пополните на https://atlorium.com",
                429, "Превышен лимит запросов — повторите позже",
                503, "Сервис распознавания временно недоступен (за сбой на своей стороне мы не списываем деньги)");

        final int status;

        AtloriumException(int status, String body) {
            super("HTTP " + status + ": "
                    + REASONS.getOrDefault(status, "Неизвестная ошибка")
                    + ". Ответ сервера: " + body.substring(0, Math.min(200, body.length())));
            this.status = status;
        }
    }

    /**
     * Сколько ждать после 429. Ноль/мусор и слишком большие значения не берём на веру:
     * 0 означал бы busy-loop, десятки минут — зависание. 0 на выходе = «ждать бессмысленно».
     */
    static int retryAfter(HttpResponse<String> response) {
        int seconds = response.headers().firstValue("Retry-After")
                .map(raw -> {
                    try {
                        return Integer.parseInt(raw.trim());
                    } catch (NumberFormatException error) {
                        return 0;
                    }
                })
                .orElse(0);

        if (seconds <= 0) {
            return RETRY_DELAY_SEC;
        }
        return seconds <= MAX_RETRY_DELAY_SEC ? seconds : 0;
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
     */
    static String imageToText(byte[] image, String mode)
            throws IOException, InterruptedException {

        String body = "{\"image\":\""
                + Base64.getEncoder().encodeToString(image)
                + "\",\"mode\":\"" + mode + "\"}";

        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/api/Ocr/image-to-text"))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            // 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
            if (response.statusCode() == 429 && attempt < MAX_RETRIES) {
                int delay = retryAfter(response);
                if (delay == 0) {
                    throw new AtloriumException(429, "лимит по IP исчерпан, повторите позже");
                }
                System.err.println("  ... лимит запросов, пауза " + delay + " с");
                Thread.sleep(delay * 1000L);
                continue;
            }

            if (response.statusCode() != 200) {
                throw new AtloriumException(response.statusCode(), response.body());
            }
            return response.body();
        }

        throw new AtloriumException(429, "лимит запросов не отпустил после повтора");
    }

    // ── Разбор JSON ──────────────────────────────────────────────────────────
    // Пример намеренно оставлен без внешних зависимостей, чтобы запускаться одной
    // командой `java Main.java`. В рабочем проекте берите Jackson или Gson и
    // маппьте ответ в полноценную запись — эти регулярки существуют только ради
    // отсутствия pom.xml.

    /**
     * Достаёт строковое поле и разворачивает JSON-экранирование. Для этого сервиса это
     * важнее обычного: поле text — многострочный Markdown, то есть переводы строк
     * приезжают как "\n" и без разворачивания вся страница слипнется в одну строку.
     */
    static String str(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return unescape(matcher.group(1));
    }

    /** Разворачивает escape-последовательности JSON-строки, включая \\uXXXX. */
    static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    // Шестнадцатеричный код символа: четыре цифры после буквы u.
                    if (i + 4 < raw.length()) {
                        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    static boolean bool(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() && "true".equals(matcher.group(1));
    }

    /** elapsedMs по спеке может прийти и числом, и строкой — принимаем оба варианта. */
    static long number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?(-?\\d+)\"?").matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    /**
     * Формат по сигнатуре файла. Сервис принимает ТОЛЬКО изображения; PDF вынесен в
     * отдельную ветку — его присылают чаще всего, и ошибка должна быть внятной.
     */
    static String imageFormat(byte[] data) {
        if (starts(data, '%', 'P', 'D', 'F')) {
            return "PDF";
        }
        if (starts(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "PNG";
        }
        if (starts(data, 0xFF, 0xD8, 0xFF)) {
            return "JPEG";
        }
        if (starts(data, 'G', 'I', 'F', '8')) {
            return "GIF";
        }
        if (starts(data, 'B', 'M')) {
            return "BMP";
        }
        if (starts(data, 'R', 'I', 'F', 'F') && data.length >= 12
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "WEBP";
        }
        if (starts(data, 0x49, 0x49, 0x2A, 0x00) || starts(data, 0x4D, 0x4D, 0x00, 0x2A)) {
            return "TIFF";
        }
        return null;
    }

    static boolean starts(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    // ── Применение данных: разбор распознанной страницы ───────────────────────
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
    record Layout(int headings, int tableRows, int lines) { }

    record Recognized(
            boolean recognized,
            String text,
            String format,
            boolean truncated,
            String mode,
            int units,
            long elapsedMs,
            Path source,
            int sizeBytes,
            String imageFormat,
            Layout layout) {

        /** Тарифицируется ли запрос. Нераспознанное изображение — бесплатно. */
        boolean charged() {
            return recognized;
        }

        /** Результат нельзя считать полным: страница распознана не до конца. */
        boolean needsReview() {
            return recognized && truncated;
        }
    }

    /** Считает структуру страницы по Markdown: заголовки, строки таблиц, строки текста. */
    static Layout analyzeLayout(String markdown) {
        int headings = 0;
        int tableRows = 0;
        int lines = 0;

        for (String raw : markdown.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            lines++;
            if (line.startsWith("#")) {
                headings++;
            } else if (line.startsWith("|")) {
                // Строка-разделитель таблицы («|---|---|») — это разметка, а не данные.
                if (line.matches("[|\\-: ]+")) {
                    continue;
                }
                tableRows++;
            }
        }

        return new Layout(headings, tableRows, lines);
    }

    static Recognized extractText(Path path, String mode)
            throws IOException, InterruptedException {

        byte[] data = Files.readAllBytes(path);

        String kind = imageFormat(data);
        if ("PDF".equals(kind)) {
            throw new IllegalArgumentException("Это PDF. Сервис распознаёт страницу-ИЗОБРАЖЕНИЕ — "
                    + "отрендерите PDF в картинки и отправьте их постранично.");
        }
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Не похоже на изображение: поддерживаются PNG, JPEG, GIF, BMP, WEBP, TIFF.");
        }
        if (data.length > MAX_IMAGE_BYTES) {
            // Locale.ROOT: иначе на ru-RU машине получится «10,4», а на CI «10.4»,
            // и вывод примера разойдётся с README.
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Изображение больше 10 МБ (%.1f МБ) — сервис такое не примет.",
                    data.length / (1024.0 * 1024.0)));
        }

        String json = imageToText(data, mode);

        // recognized — ключевое поле контракта: читаемый текст найден и лежит в text.
        boolean recognized = bool(json, "recognized");
        String text = str(json, "text");
        String rawFormat = str(json, "format");
        String format = FORMAT_MARKDOWN.equals(rawFormat) ? FORMAT_MARKDOWN : FORMAT_PLAIN;

        Layout layout = null;
        if (recognized && text != null && !text.isEmpty() && FORMAT_MARKDOWN.equals(format)) {
            layout = analyzeLayout(text);
        }

        // Режим, в котором изображение распознано на самом деле; сервер его всегда
        // заполняет, но на всякий случай подстрахуемся значением "auto".
        String responseMode = str(json, "mode");
        if (responseMode == null) {
            responseMode = "auto";
        }

        return new Recognized(
                recognized,
                text,
                format,
                bool(json, "truncated"),
                responseMode,
                (int) number(json, "units"),
                number(json, "elapsedMs"),
                path,
                data.length,
                kind,
                layout);
    }

    /** Печатает распознанный текст, ограничивая вывод разумным числом строк. */
    static void printText(String text) {
        List<String> lines = List.of(text.replaceAll("[\\r\\n]+$", "").split("\n", -1));
        System.out.println("--- начало распознанного текста ---");
        for (int i = 0; i < Math.min(PREVIEW_LINES, lines.size()); i++) {
            System.out.println(lines.get(i));
        }
        if (lines.size() > PREVIEW_LINES) {
            System.out.println("... ещё " + (lines.size() - PREVIEW_LINES)
                    + " строк(и) — полностью лежат в поле text");
        }
        System.out.println("--- конец распознанного текста ---");
    }

    public static void main(String[] args) throws Exception {
        // Образец лежит в КОРНЕ репозитория — один на все шесть примеров.
        Path path = Paths.get("..", "sample.png");
        // --mode <значение>; без него запрос уходит в режиме "auto".
        String mode = "auto";
        for (int i = 0; i < args.length; i++) {
            if ("--mode".equals(args[i])) {
                if (i + 1 >= args.length) {
                    System.err.println("После --mode нужно указать режим.");
                    System.exit(1);
                }
                mode = args[++i];
            } else {
                path = Paths.get(args[i]);
            }
        }

        if (API_KEY.equals(SANDBOX_KEY)) {
            System.out.println("Демо-ключ: сервис ВЕРНЁТ СГЕНЕРИРОВАННУЮ СТРАНИЦУ (мок), а не результат");
            System.out.println("настоящего распознавания вашего изображения. Контракт, разметка и формат");
            System.out.println("ответа — настоящие; качество распознавания проверяется боевым ключом.\n");
        }

        Recognized result;
        try {
            result = extractText(path, mode);
        } catch (IOException | IllegalArgumentException | AtloriumException error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
            return;
        }

        System.out.println("Файл: " + result.source().getFileName()
                + " · " + result.imageFormat() + " · " + result.sizeBytes() + " байт");

        if (!result.recognized()) {
            System.out.println("Время обработки: " + result.elapsedMs() + " мс");
            System.out.println("Режим: " + result.mode() + ", единиц работы: " + result.units());
            System.out.println("\nВердикт: читаемого текста на изображении не найдено — плата НЕ взимается.");
            System.out.println("Попробуйте поднять разрешение (150-300 dpi), увеличить контраст,");
            System.out.println("выровнять страницу или обрезать поля.");
            return;
        }

        if (FORMAT_MARKDOWN.equals(result.format())) {
            System.out.println("Формат ответа: markdown — содержимое страницы с разметкой");
            if (result.layout() != null) {
                System.out.println("Структура: заголовков - " + result.layout().headings()
                        + ", строк таблиц - " + result.layout().tableRows()
                        + ", строк текста - " + result.layout().lines());
            }
        } else {
            System.out.println("Формат ответа: plain — простая строка без разметки");
        }

        System.out.println("Время обработки: " + result.elapsedMs() + " мс");
        System.out.println("Режим: " + result.mode() + ", единиц работы: " + result.units() + "\n");
        printText(result.text() == null ? "" : result.text());

        if (result.needsReview()) {
            System.out.println("\nВердикт: страница распознана НЕ ПОЛНОСТЬЮ — ответ оборван по длине.");
            System.out.println("Отправьте страницу на ручную проверку: часть содержимого в текст не попала.");
            System.out.println("Использовать такой результат как полный нельзя.");
        } else {
            System.out.println("\nВердикт: страница распознана полностью — запрос тарифицируется.");
        }
    }
}
