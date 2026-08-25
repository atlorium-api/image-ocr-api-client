// Клиент API распознавания текста с изображения (OCR) Atlorium —
// страница документа → Markdown.
//
// Запуск (работает сразу, без регистрации — на демо-ключе):
//
//	go run .
//	go run . ../sample.png --mode digits
//
// Боевой ключ: получить на https://atlorium.com и положить в переменную окружения
// ATLORIUM_API_KEY. Код при этом не меняется.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

// SandboxKey — публичный демо-ключ. С ним API отвечает МОКОМ: возвращается
// сгенерированная страница документа, а НЕ результат настоящего распознавания
// вашей картинки. Ответ детерминирован (seed берётся из самой картинки) — на нём
// можно писать стабильные тесты, но качество распознавания он не показывает.
const SandboxKey = "ak_sandbox_demo_mockdata_v1"

const (
	// Распознавание синхронное: ответ приходит в том же HTTP-вызове и на плотной
	// странице занимает секунды. Таймаут с запасом.
	timeout = 120 * time.Second

	retryDelay = 20 * time.Second
	maxRetries = 1

	// Потолок ожидания при 429. Исчерпав часовое окно, сервер честно просит подождать
	// десятки минут — клиент, слепо доверяющий Retry-After, зависнет на всё это время
	// (а в CI просто съест бюджет джоба). Дольше потолка не ждём.
	maxRetryDelay = 120 * time.Second

	// Верхняя граница размера изображения на стороне сервиса — 10 МБ в декодированном виде.
	maxImageBytes = 10 * 1024 * 1024

	// Сколько строк распознанного текста печатать: страница документа длиннее экрана.
	previewLines = 40

	// Значения поля format ответа.
	formatMarkdown = "markdown"
	formatPlain    = "plain"
)

var (
	apiKey  = envOr("ATLORIUM_API_KEY", SandboxKey)
	baseURL = envOr("ATLORIUM_BASE_URL", "https://atlorium.com")
	client  = &http.Client{Timeout: timeout}
)

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// ImageOcrRequest — тело POST /api/Ocr/image-to-text.
type ImageOcrRequest struct {
	// Image — изображение страницы в Base64: «голый» base64 или data-URL
	// "data:image/png;base64,...".
	Image string `json:"image"`
	// Mode — режим распознавания:
	//   "auto"     — сервис сам различает короткую строку и страницу;
	//   "document" — страница целиком, с сохранением структуры;
	//   "table"    — только таблица;
	//   "formula"  — формула, ответ в разметке LaTeX;
	//   "line"     — короткая строка из букв и цифр;
	//   "digits"   — короткая строка из одних цифр.
	// Неизвестное значение сервис отвергает кодом 400, а не подменяет молча.
	Mode string `json:"mode"`
}

// ImageOcrResponse — ответ сервиса.
type ImageOcrResponse struct {
	// Recognized: true — содержимое страницы распознано и лежит в Text;
	// false — читаемого текста не найдено, деньги не списаны.
	Recognized bool   `json:"recognized"`
	Text       string `json:"text"`
	// Format — чем является Text: "markdown" (разметка страницы), "plain"
	// (простая строка) или "latex" (формула). Неизвестное значение разбираем как "plain".
	Format string `json:"format"`
	// Truncated — ответ оборван по длине: страница распознана НЕ полностью.
	Truncated bool `json:"truncated"`
	// Mode — режим, в котором изображение распознано на самом деле (для "auto" — решение сервиса).
	Mode string `json:"mode"`
	// Units — единицы работы, в которые обошёлся запрос: по ним он и тарифицирован.
	Units int `json:"units"`
	// ElapsedMs — время обработки в мс. По спеке допустимы и число, и строка,
	// поэтому json.Number, а не int64.
	ElapsedMs json.Number `json:"elapsedMs"`
}

// APIError раскладывает HTTP-код в человекочитаемую причину.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string {
	reasons := map[int]string{
		400: "изображение не передано, битый Base64 или размер больше 10 МБ (запрос НЕ тарифицируется)",
		401: "API-ключ отсутствует, просрочен или недействителен",
		402: "недостаточно кредитов на балансе — пополните на https://atlorium.com",
		429: "превышен лимит запросов — повторите позже",
		503: "сервис распознавания временно недоступен (за сбой на своей стороне мы не списываем деньги)",
	}
	reason, ok := reasons[e.Status]
	if !ok {
		reason = "неизвестная ошибка"
	}
	return fmt.Sprintf("HTTP %d: %s. Ответ сервера: %s", e.Status, reason, e.Body)
}

// retryAfter — сколько ждать после 429. Ноль/мусор и слишком большие значения не
// берём на веру: 0 означал бы busy-loop, десятки минут — зависание клиента.
// Нулевая длительность на выходе = «ждать бессмысленно, сдавайся».
func retryAfter(response *http.Response) time.Duration {
	seconds, err := strconv.Atoi(response.Header.Get("Retry-After"))
	if err != nil || seconds <= 0 {
		return retryDelay
	}
	delay := time.Duration(seconds) * time.Second
	if delay > maxRetryDelay {
		return 0
	}
	return delay
}

// ImageToText вызывает POST /api/Ocr/image-to-text — единственный эндпоинт сервиса.
func ImageToText(image []byte, mode string) (*ImageOcrResponse, error) {
	payload, err := json.Marshal(ImageOcrRequest{
		Image: base64.StdEncoding.EncodeToString(image),
		Mode:  mode,
	})
	if err != nil {
		return nil, err
	}

	for attempt := 0; attempt <= maxRetries; attempt++ {
		request, err := http.NewRequest(http.MethodPost, baseURL+"/api/Ocr/image-to-text", bytes.NewReader(payload))
		if err != nil {
			return nil, err
		}
		request.Header.Set("Authorization", "Bearer "+apiKey)
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Accept", "application/json")

		response, err := client.Do(request)
		if err != nil {
			return nil, err
		}

		body, err := io.ReadAll(response.Body)
		response.Body.Close()
		if err != nil {
			return nil, err
		}

		// 429 — не поломка, а реальный лимит продукта. Ждём и повторяем один раз.
		if response.StatusCode == http.StatusTooManyRequests && attempt < maxRetries {
			delay := retryAfter(response)
			if delay == 0 {
				return nil, &APIError{Status: 429, Body: "лимит по IP исчерпан, повторите позже"}
			}
			fmt.Fprintf(os.Stderr, "  ... лимит запросов, пауза %s\n", delay)
			time.Sleep(delay)
			continue
		}

		if response.StatusCode != http.StatusOK {
			return nil, &APIError{Status: response.StatusCode, Body: string(body)}
		}

		var result ImageOcrResponse
		if err := json.Unmarshal(body, &result); err != nil {
			return nil, err
		}
		return &result, nil
	}

	return nil, &APIError{Status: 429, Body: "лимит запросов не отпустил после повтора"}
}

// imageFormat определяет формат по сигнатуре файла. Сервис принимает ТОЛЬКО
// изображения; PDF вынесен в отдельную ветку — его присылают чаще всего, и ошибка
// должна быть внятной.
func imageFormat(data []byte) string {
	switch {
	case bytes.HasPrefix(data, []byte("%PDF")):
		return "PDF"
	case bytes.HasPrefix(data, []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}):
		return "PNG"
	case bytes.HasPrefix(data, []byte{0xff, 0xd8, 0xff}):
		return "JPEG"
	case bytes.HasPrefix(data, []byte("GIF8")):
		return "GIF"
	case bytes.HasPrefix(data, []byte("BM")):
		return "BMP"
	case bytes.HasPrefix(data, []byte("RIFF")) && len(data) >= 12 && bytes.Equal(data[8:12], []byte("WEBP")):
		return "WEBP"
	case bytes.HasPrefix(data, []byte{0x49, 0x49, 0x2a, 0x00}), bytes.HasPrefix(data, []byte{0x4d, 0x4d, 0x00, 0x2a}):
		return "TIFF"
	default:
		return ""
	}
}

// ── Применение данных: разбор распознанной страницы ───────────────────────────
// Ответ сам по себе — просто JSON. Ценность появляется, когда по нему принимают
// решение. Здесь решение принимается по трём полям сразу:
//
//	Recognized — читаемый текст найден. Плата берётся ТОЛЬКО за Recognized=true;
//	             Recognized=false — деньги не списаны, изображение можно улучшить
//	             и отправить снова, ничего не заплатив.
//	Format     — чем является Text: "markdown" (страница с заголовками, абзацами,
//	             таблицами и формулами), "plain" (простая строка, режимы line/digits)
//	             или "latex" (формула). Список значений может пополниться, поэтому
//	             НЕизвестное значение разбираем как "plain" — так советует сам контракт.
//	Truncated  — ответ оборван по длине, страница распознана НЕ ПОЛНОСТЬЮ. Это
//	             самое коварное поле: обрезанный текст выглядит совершенно
//	             нормальным, и без явной проверки потеря части документа пройдёт
//	             незамеченной. Поэтому она поднимается до вердикта «требуется
//	             ручная проверка», а не прячется в лог.

// Layout — структура распознанной страницы, посчитанная по Markdown-разметке.
type Layout struct {
	Headings  int
	TableRows int
	Lines     int
}

// Recognized — результат распознавания файла.
type Recognized struct {
	Recognized  bool
	Text        string
	Format      string
	Truncated   bool
	Mode        string
	Units       int
	ElapsedMs   int64
	Source      string
	SizeBytes   int
	ImageFormat string
	Layout      *Layout
}

// Charged сообщает, тарифицируется ли запрос. Нераспознанное изображение — бесплатно.
func (r Recognized) Charged() bool { return r.Recognized }

// NeedsReview: результат нельзя считать полным — страница распознана не до конца.
func (r Recognized) NeedsReview() bool { return r.Recognized && r.Truncated }

// analyzeLayout считает структуру страницы по Markdown: заголовки, строки таблиц,
// строки текста.
func analyzeLayout(markdown string) Layout {
	var layout Layout

	for _, raw := range strings.Split(markdown, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		layout.Lines++
		switch {
		case strings.HasPrefix(line, "#"):
			layout.Headings++
		case strings.HasPrefix(line, "|"):
			// Строка-разделитель таблицы («|---|---|») — это разметка, а не данные.
			if strings.Trim(line, "|-: ") == "" {
				continue
			}
			layout.TableRows++
		}
	}

	return layout
}

// ExtractText читает файл с диска, кодирует в Base64 и отправляет на распознавание.
func ExtractText(path string, mode string) (*Recognized, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	kind := imageFormat(data)
	switch kind {
	case "PDF":
		return nil, fmt.Errorf("это PDF: сервис распознаёт страницу-ИЗОБРАЖЕНИЕ — " +
			"отрендерите PDF в картинки и отправьте их постранично")
	case "":
		return nil, fmt.Errorf("не похоже на изображение: поддерживаются PNG, JPEG, GIF, BMP, WEBP, TIFF")
	}
	if len(data) > maxImageBytes {
		return nil, fmt.Errorf("изображение больше 10 МБ (%.1f МБ) — сервис такое не примет",
			float64(len(data))/(1024*1024))
	}

	result, err := ImageToText(data, mode)
	if err != nil {
		return nil, err
	}

	elapsed, _ := result.ElapsedMs.Int64()

	// Recognized — ключевое поле контракта: читаемый текст найден и лежит в Text.
	format := formatPlain
	if result.Format == formatMarkdown {
		format = formatMarkdown
	}

	// Режим ответа сервер всегда заполняет; на всякий случай подстрахуемся значением "auto".
	responseMode := result.Mode
	if responseMode == "" {
		responseMode = "auto"
	}

	recognized := &Recognized{
		Recognized:  result.Recognized,
		Text:        result.Text,
		Format:      format,
		Truncated:   result.Truncated,
		Mode:        responseMode,
		Units:       result.Units,
		ElapsedMs:   elapsed,
		Source:      path,
		SizeBytes:   len(data),
		ImageFormat: kind,
	}
	if recognized.Recognized && recognized.Text != "" && format == formatMarkdown {
		layout := analyzeLayout(recognized.Text)
		recognized.Layout = &layout
	}

	return recognized, nil
}

// printText печатает распознанный текст, ограничивая вывод разумным числом строк.
func printText(text string) {
	lines := strings.Split(strings.TrimRight(text, "\r\n"), "\n")
	fmt.Println("--- начало распознанного текста ---")
	for i, line := range lines {
		if i >= previewLines {
			fmt.Printf("... ещё %d строк(и) — полностью лежат в поле text\n", len(lines)-previewLines)
			break
		}
		fmt.Println(line)
	}
	fmt.Println("--- конец распознанного текста ---")
}

func main() {
	// Образец лежит в КОРНЕ репозитория — один на все шесть примеров.
	path := filepath.Join("..", "sample.png")
	// --mode <значение>; без него запрос уходит в режиме "auto".
	mode := "auto"
	args := os.Args[1:]
	for i := 0; i < len(args); i++ {
		if args[i] == "--mode" {
			if i+1 >= len(args) {
				fmt.Fprintln(os.Stderr, "После --mode нужно указать режим.")
				os.Exit(1)
			}
			mode = args[i+1]
			i++
			continue
		}
		path = args[i]
	}

	if apiKey == SandboxKey {
		fmt.Println("Демо-ключ: сервис ВЕРНЁТ СГЕНЕРИРОВАННУЮ СТРАНИЦУ (мок), а не результат")
		fmt.Println("настоящего распознавания вашего изображения. Контракт, разметка и формат")
		fmt.Println("ответа — настоящие; качество распознавания проверяется боевым ключом.")
		fmt.Println()
	}

	result, err := ExtractText(path, mode)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		os.Exit(1)
	}

	fmt.Printf("Файл: %s · %s · %d байт\n", filepath.Base(result.Source), result.ImageFormat, result.SizeBytes)

	if !result.Recognized {
		fmt.Printf("Время обработки: %d мс\n", result.ElapsedMs)
		fmt.Printf("Режим: %s, единиц работы: %d\n", result.Mode, result.Units)
		fmt.Println("\nВердикт: читаемого текста на изображении не найдено — плата НЕ взимается.")
		fmt.Println("Попробуйте поднять разрешение (150-300 dpi), увеличить контраст,")
		fmt.Println("выровнять страницу или обрезать поля.")
		return
	}

	if result.Format == formatMarkdown {
		fmt.Println("Формат ответа: markdown — содержимое страницы с разметкой")
		if result.Layout != nil {
			fmt.Printf("Структура: заголовков - %d, строк таблиц - %d, строк текста - %d\n",
				result.Layout.Headings, result.Layout.TableRows, result.Layout.Lines)
		}
	} else {
		fmt.Println("Формат ответа: plain — простая строка без разметки")
	}

	fmt.Printf("Время обработки: %d мс\n", result.ElapsedMs)
	fmt.Printf("Режим: %s, единиц работы: %d\n\n", result.Mode, result.Units)
	printText(result.Text)

	if result.NeedsReview() {
		fmt.Println("\nВердикт: страница распознана НЕ ПОЛНОСТЬЮ — ответ оборван по длине.")
		fmt.Println("Отправьте страницу на ручную проверку: часть содержимого в текст не попала.")
		fmt.Println("Использовать такой результат как полный нельзя.")
	} else {
		fmt.Println("\nВердикт: страница распознана полностью — запрос тарифицируется.")
	}
}
