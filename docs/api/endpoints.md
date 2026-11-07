# Endpoint inventory — Stage 2 (version 1.1.0)

Full specification: [`openapi.yaml`](openapi.yaml) (OpenAPI 3.0.3).
All responses are JSON with `snake_case` fields; every error has the body
`{"error": "<code>", "message": "<text>"}`.

## 1. Front-end API

**Provider:** Search Service (`http://localhost:7003`) · **Consumer:** front-end, or any HTTP
client exercising the contract. CORS is enabled (`CORS_ORIGIN`, any origin by default).

| Method | Route | Purpose | Success | Errors |
|--------|-------|---------|---------|--------|
| GET | `/search?q=&author=&language=&year=&offset=&limit=` | Keyword search with optional filters and pagination | 200 | 400 `invalid_parameter`, 500 |
| GET | `/books/{book_id}` | Metadata of one indexed book | 200 | 400, 404 `not_found`, 500 |
| GET | `/search/filters` | Languages, years and authors available for the filters | 200 | 500 |

**Search semantics**

| Aspect | Rule |
|--------|------|
| `q` (required) | Tokenized like the index (lowercase, separators = anything outside `a-z`). A book matches when it contains **all** the terms. 1–10 distinct terms. |
| `author` | Case-insensitive substring of the author. |
| `language` | Exact ISO 639-1 code (`en`, `es`, `fr`…), 2–3 letters. |
| `year` | Exact year of the Gutenberg release date. |
| Combination | All the given filters must hold (AND). |
| Pagination | `offset` default 0 (0–100000); `limit` default 20 (1–100). `total` counts every match; `results` holds only the page. |
| Ordering | `hits` (occurrences of the query terms) descending, then `book_id` ascending. |
| No matches | `200` with `total: 0` and `results: []`. |
| Consistency | Count and page are read from the same committed snapshot. |
| Retries | Read-only, safe to retry. |

## 2. Internal APIs

Consumed by the **Control Module** (orchestration). They are not part of the front-end
contract: the front-end never triggers ingestion or indexing.

### Ingestion Service (`http://localhost:7001`)

| Method | Route | Purpose | Completion | Idempotency / retries |
|--------|-------|---------|------------|-----------------------|
| POST | `/ingest/{book_id}` | Download a book and store header/body in the datalake | Synchronous: `200` = both files written and recorded | Repeating it returns `already_available`, no second download. Retry on timeout or `502`; `404`/`422` are permanent |
| GET | `/ingest/status/{book_id}` | `available` / `not_available` | — | Read-only |
| GET | `/ingest/list` | Ids of the books available in the datalake | — | Read-only |

### Indexing Service (`http://localhost:7002`)

| Method | Route | Purpose | Completion | Idempotency / retries |
|--------|-------|---------|------------|-----------------------|
| POST | `/index/update/{book_id}` | Extract metadata and update the index for one book | Synchronous: `200` = transaction committed, already searchable | Reindexing replaces the rows (no duplicates). Retry on timeout or `5xx`; `404` = ingest first; `409` = a rebuild is running, retry later |
| POST | `/index/rebuild` | Rebuild everything from the datalake | Synchronous, one transaction: searches see the old index until the commit | One rebuild at a time (`409 rebuild_in_progress`) |
| GET | `/index/status` | Books indexed, last update, index size, terms and postings | — | Read-only |

**Caller timeouts** (Control Module): 60 s for ingestion, 120 s for indexing. On timeout the
caller checks the corresponding status endpoint before retrying with backoff.

**Workflow order:** `POST /ingest/{id}` → `200` → `POST /index/update/{id}` → `200` → the book
is searchable through `GET /search`.

## 3. Operational endpoints

| Method | Route | Provider | Consumers | Purpose |
|--------|-------|----------|-----------|---------|
| GET | `/status` | Every service (ports 7000–7003) | Control Module, operators | Health check |
| GET | `/control/status` | Control Module (7000) | Operators | Downloaded / indexed / failed / pending books |
| GET | `/control/metrics` | Control Module (7000) | Operators, benchmarks | Coordination cost since start-up: books per second, average ingestion / indexing time, retries, backoff |

## Implementation status and validation

Every endpoint of this inventory is implemented.

| Check | What it covers | How to run it |
|-------|----------------|---------------|
| `SearchApiTest` and the tests of each service | Front-end contract (search, filters, empty results, invalid parameters, pagination, book details, filter values) and the internal services | `./mvnw test` |
| [`check_spec.py`](check_spec.py) | Calls the 24 documented cases of the four running services and checks every response against `openapi.yaml` (status code, required and undocumented fields, types, enums) | Start the services with the sample books, then `python docs/api/check_spec.py docs/api/openapi.yaml` (needs `pip install pyyaml`). Expected: `24/24 responses match the specification` |
| [`examples/*.http`](examples) | Runnable requests with the expected result written above each one | IntelliJ HTTP client or `curl` |
