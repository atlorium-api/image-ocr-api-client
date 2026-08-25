# OCR API — extract text, tables and formulas from an image (image to text)

[Русский](README.md) · **English**

[![Live API tests](https://github.com/atlorium-api/image-ocr-api-client/actions/workflows/examples.yml/badge.svg)](https://github.com/atlorium-api/image-ocr-api-client/actions/workflows/examples.yml)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-Swagger-brightgreen)](https://atlorium.com/ocrAPI)

Ready-to-run examples for the **OCR REST API** in six languages: **Python, TypeScript (Node.js), Go, Java, C#, PHP.**
**Extract the contents of a document page from an image** in a single HTTP call: the image goes out as **Base64**, and **Markdown** comes back — headings, paragraphs, **tables** and **formulas**, with the page structure preserved. Not "characters on one line". This is the only POST service in the Atlorium set, and every example shows how to build and send a JSON body with an image inside, correctly.

Every example **runs out of the box — no signup, no key, no card.** A public demo key is baked in, and a sample image `sample.png` ships with the repo, so you do not have to hunt for a picture first.

```bash
git clone https://github.com/atlorium-api/image-ocr-api-client
cd image-ocr-api-client/python && pip install -r requirements.txt && python main.py
```

The examples print their report in Russian (the primary market of the service); the API itself is language-neutral.

```
Файл: sample.png · PNG · 240 байт
Формат ответа: markdown — содержимое страницы с разметкой
Структура: заголовков - 1, строк таблиц - 4, строк текста - 9
Время обработки: 1183 мс
Режим: document, единиц работы: 4

--- начало распознанного текста ---
# Счёт-фактура № 4821 от 14.03.2025

**Поставщик:** ООО «Гарант-Сервис»
**Покупатель:** ЗАО «Северный ветер»

| № | Наименование | Кол-во | Цена | Сумма |
|---|---|---|---|---|
| 1 | Кабель силовой ВВГнг 3х2.5 | 7 | 1234,50 | 8641,50 |
| 2 | Автомат защиты 16А | 3 | 890,00 | 2670,00 |
| 3 | Щит распределительный ЩРН-24 | 12 | 445,20 | 5342,40 |

**Итого:** 16653,90 руб.
--- конец распознанного текста ---

Вердикт: страница распознана полностью — запрос тарифицируется.
```

> **The demo key does not read your image.** It returns a **generated** document (`sample.png` actually says "ATLORIUM", yet the response is an invoice). That is deliberate: the sandbox lets you verify the **contract, the response shape and your integration** — not recognition quality. The **shape is real** though: a heading, paragraphs and a Markdown table whose rows add up to the stated total, exactly as a live key returns — so parsing code written against the sandbox keeps working in production. Mock responses are deterministic (seeded from the image itself), so they are safe to assert on in tests.

---

## What it is for

- **Document and scan digitisation** — turn a photo of an invoice, receipt, act or form into text you can search and feed into an accounting system.
- **Lifting tables out of scans** — the tabular part comes back as a Markdown table with rows and columns, not as a soup of numbers on one line.
- **Study and research material** — mathematical expressions are recognized as formulas, not as a random pile of symbols.
- **Dataset labelling** — pull text off images in bulk instead of by hand.
- **Accessibility** — extract text from images for visually impaired users.

The examples do not just print JSON — they **apply** it. Each ships an `extractText()` function that reads a file from disk, checks its format and size locally, encodes it to Base64, sends the POST — and rules on three fields at once.

### The three fields the verdict is built on

| Field | What `extractText()` does with it |
|-------|-----------------------------------|
| `recognized` | No readable text found — say so and suggest what to improve in the scan. **That request is not charged.** |
| `format` | `markdown` — parse the page markup and report its structure (headings, table rows, text lines; see `analyzeLayout()`). `plain` — print the string as is. **An unknown value is treated as `plain`** — the contract says so, and it shields your integration from future additions to the list. |
| `truncated` | The response was cut off by length: the page was **not** recognized in full. The examples do not bury this in a log — it is raised to a verdict of "this result needs manual review". |

`truncated` deserves a word of its own. It is the most treacherous field in the contract: **truncated text looks perfectly normal** — heading in place, table in place — so without an explicit check the loss of part of the document goes unnoticed and an incomplete document travels down your pipeline. The check costs one line, which is why all six examples have it.

### You pay only for successful recognition

`recognized=false` means no readable text could be found — and **you are not charged** for that request. Same for `503`: our failure or overload, our cost; and for `400`, your own malformed request, is not charged either. In practice: run a poor scan, see `recognized=false`, improve it (deskew, remove the fold shadow, raise contrast) and retry — the bill does not grow. That is why all six examples check `recognized` first.

## Quick start

Try the API without cloning anything (the image is Base64-encoded inline):

```bash
curl -X POST "https://atlorium.com/api/Ocr/image-to-text" \
     -H "Authorization: Bearer ak_sandbox_demo_mockdata_v1" \
     -H "Content-Type: application/json" \
     -d "{\"image\":\"$(base64 -w0 sample.png)\"}"
```

```json
{
  "recognized": true,
  "text": "# Счёт-фактура № 4821 от 14.03.2025\n\n**Поставщик:** ...",
  "format": "markdown",
  "truncated": false,
  "mode": "document",
  "units": 4,
  "elapsedMs": 1183
}
```

| Language | Run | Requires |
|----------|-----|----------|
| [Python](python/) | `pip install -r requirements.txt && python main.py` | Python 3.10+ |
| [TypeScript / Node.js](node/) | `npm install && npm start` | Node.js 20+ |
| [Go](go/) | `go run .` | Go 1.22+ |
| [Java](java/) | `java Main.java` | JDK 17+ (no dependencies) |
| [C#](csharp/) | `dotnet run` | .NET 8+ |
| [PHP](php/) | `php main.php` | PHP 8.1+ |

Pass your own image: `python main.py /path/to/scan.jpg`
Recognition mode via `--mode`: `python main.py ../sample.png --mode digits` (values: `auto`, `document`, `table`, `formula`, `line`, `digits`; default `auto`).

With no argument the examples fall back to `sample.png` in the repository root — `../sample.png` resolves identically from every language folder.

## Authentication

The key goes in the `Authorization` header:

```
Authorization: Bearer YOUR_KEY
```

| Key | Behaviour |
|-----|-----------|
| `ak_sandbox_demo_mockdata_v1` | **Demo key.** Public, shared by everyone. Returns a mock — a generated document in the real response format, not a reading of your image. Charges nothing, needs no account. Responses are deterministic, so you can assert on them in tests. |
| Live key | Real recognition. Get one at [atlorium.com](https://atlorium.com) |

Switching to a live key requires **no code changes** — every example reads an environment variable:

```bash
export ATLORIUM_API_KEY="ak_your_live_key"
```

Every sandbox response carries the header `X-Atlorium-Sandbox: true`, so a mock can never be mistaken for real recognition.

## Endpoints

Base URL: `https://atlorium.com`

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/Ocr/image-to-text` | Recognize the contents of a page. The service's only endpoint |

### `POST /api/Ocr/image-to-text`

Request body (`ImageOcrRequest`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `image` | string | yes | The image as **Base64**. Both raw base64 and a data-URL (`data:image/png;base64,...`) are accepted |
| `mode` | string | no | **Recognition mode.** Defaults to `auto` — the service tells a short line from a full page itself. Values: `auto`, `document` (whole page), `table` (a single table), `formula` (a LaTeX formula), `line` (a line of letters and digits), `digits` (digits only). An unknown value is rejected with `400`, not silently substituted |

There are no other request fields.

```json
{
  "image": "iVBORw0KGgoAAAANSUhEUgAA..."
}
```

## Response fields

`ImageOcrResponse`:

| Field | Type | Meaning |
|-------|------|---------|
| `recognized` | bool | **The key field.** `true` — the page contents were recognized, see `text`. `false` — no readable text found; **you are not charged** |
| `text` | string \| null | The page contents. `null` when `recognized=false` |
| `format` | string | What `text` actually is: `"markdown"` — a page with markup (headings, paragraphs, lists, tables); `"plain"` — a plain string (`line`/`digits` modes); `"latex"` — a formula. Pick your parsing branch from this field rather than guessing from the content. The list of values may grow — treat an unknown value as `plain` |
| `truncated` | bool | `true` — the text was **cut off** by an internal length limit and the page was **not** recognized in full. Always check it: a truncated response is indistinguishable from a complete one, and without this check the loss goes unnoticed. Rare — unusually dense pages |
| `mode` | string | The mode the image was **actually** recognized in. Matches the requested one except for `auto`, where it reveals the service's decision (`line` for a short string, `document` for a page). If the result is not what you expected, look here first |
| `units` | int | **Work units** the request cost — and what it is billed by. A short line is always `1`; a page is as many as the regions recognized on it. Lets you total spend across a batch without opening the dashboard |
| `elapsedMs` | int64 | Processing time in milliseconds — including time spent queueing, not just the recognition itself. That makes it a useful dial for batch concurrency: if it grows on unchanged images, the pipeline has hit the service throughput and adding threads will not help. The spec allows both a number and a string — the examples accept either |

## Service boundaries — what it does NOT do

Stated up front, so you do not waste time:

- **Raster images only** — PNG, JPEG, GIF, BMP, WEBP, TIFF. **PDF is not accepted**: rasterise a multi-page file into page images and send them one at a time. As a bonus you control dpi and colour yourself, and thus the speed of the batch. All six examples sniff the file signature and call out PDF explicitly instead of failing obscurely.
- **Image size up to 10 MB** decoded (i.e. before Base64 encoding). An A4 scan at 300 dpi fits comfortably. Larger images get a `400` and are not billed; the examples check the size locally so a doomed request is never sent. That is a ceiling, not a recommendation: above 300 dpi accuracy stops improving, so 150–300 dpi is the sensible range.
- **No block coordinates.** Page structure arrives as markup inside `text`; there are no separate fields for word coordinates or alternative readings.
- **Handwriting is not claimed.** The service targets printed documents. It may read a handwritten line, or it may return plausible nonsense, and the response gives you no way to tell the two apart.
- **No confidence scores.** There is only `recognized`: yes or no. You cannot build a "route to a human below 0.8" threshold — format checks and checksums on your side play that role.
- **The result has no legal standing.** Recognized text is a convenient searchable copy, not a document. This is recognition, not authenticity verification: cross-checking against the original is on you.
- **Truncation (`truncated`) cannot be reproduced in the sandbox** — the mock always fits the limit. You have to write that branch blind, and it is exactly the branch that will bite in production if you skip it. Hence it exists in all six examples.
- **The demo key tells you nothing about recognition quality.** It returns a mock: a generated document instead of your image's contents. The sandbox proves the contract and your integration; accuracy is only measurable with a live key.

## Error handling

| Code | Cause | What to do |
|------|-------|------------|
| `400` | No image, malformed Base64, or larger than 10 MB | Check encoding and size. **Not billed** |
| `401` | Key missing, expired or invalid | Check the `Authorization` header |
| `402` | Insufficient credit balance | Top up at [atlorium.com](https://atlorium.com) |
| `429` | Rate limit exceeded | Retry with backoff. The examples cap the wait and report an exhausted quota instead of hanging |
| `503` | Recognition service temporarily unavailable or overloaded | Retry later. **You are not charged for our failures** |

All six examples map these codes to human-readable causes — see the `AtloriumError` class.

Note: `recognized=false` is **not an error**. It is a normal `200` response meaning the image was processed but held no readable text. No charge.

## Pricing

**Pay-as-you-go, no subscription** — you pay only for **successfully recognized** pages, and by actual volume: in **work units** (the `units` field) — a short line is one unit, a dense page is several. An unreadable image is free.

Sandbox limits are the same ones a registered user gets, on purpose: you should see the terms before paying, not after. The difference with a live key is that the limit is counted per key rather than per the shared public IP of the demo key.

Current prices and limits: **[atlorium.com/pricing](https://atlorium.com/pricing)**

## FAQ

**How do I send an image to the API?** Read the file into bytes, Base64-encode it, put it in the `image` field of the JSON POST body. That is exactly what `extractText()` does in each of the six examples — copy it wholesale.

**What do I get back?** In the default mode, the page contents as **Markdown**: headings, paragraphs, lists, tables, formulas. The `format` field states this explicitly, so you never have to guess the parsing route from the content.

**Are tables recognized?** Yes. The tabular part comes back as a Markdown table preserving rows, columns and reading order — no cell cropping needed. Still validate the values on your side: the row sums should match the stated total.

**Can I post a browser data-URL?** Yes. A string like `data:image/png;base64,iVBORw0...` is accepted as is; the service strips the prefix.

**Does it do OCR on PDF files?** No. The service works with a **raster page image** only. Rasterise the PDF first and send the pages one by one.

**Is Russian supported?** Yes, alongside English and several other languages.

**What if `recognized=false`?** You pay nothing. Improve the scan: raise the resolution (150–300 dpi), deskew it, remove the fold shadow, raise contrast. Re-sending the same file unchanged is pointless — it will produce the same result.

**What if `truncated=true`?** Treat the page as **not fully** recognized and send it for manual review. If it keeps happening on the same document type, cut the dense page into parts and recognize them separately.

**What is the maximum image size?** 10 MB decoded. An A4 scan at 300 dpi fits comfortably, so there is no need to compress the page. Going above 300 dpi does not help: accuracy plateaus while processing takes longer — noticeable across a batch.

**Do I need to register to try it?** No. The demo key is public and works without an account — but it returns a mock, not a reading of your image.

## Other Atlorium APIs

Recognized documents usually have to be checked against something. The same account and key also give you:

- [EGRUL/EGRIP](https://github.com/atlorium-api/egrul-api-client) — Russian company check by INN/OGRN: status, address, capital
- [AI chat](https://github.com/atlorium-api/ai-chat-api-client) — models, sessions, text summarization
- [CBR BIC directory](https://github.com/atlorium-api/cbr-bik-api-client) — bank details and payment account checksum
- [Email verification](https://github.com/atlorium-api/email-verification-api-client) — syntax, MX records, disposable addresses
- [SWIFT/BIC](https://github.com/atlorium-api/swift-bic-api-client) — ISO-9362 code parsing and pre-transfer checks
- [Phone validation](https://github.com/atlorium-api/phone-validation-api-client) — format, line type, range operator

Full catalogue: [atlorium.com](https://atlorium.com)

## Links

- **API reference (Swagger):** [atlorium.com/ocrAPI](https://atlorium.com/ocrAPI)
- **OpenAPI spec:** [ocr_en-US.json](https://atlorium.com/openapi/ocr_en-US.json)
- **Support:** support@atlorium.com

## License

[MIT](LICENSE)
