# Ingestion Service

Downloads books from Project Gutenberg, splits them into header and body and stores them in
the range-based datalake shared with the Indexing Service. Port **7001**. Owner: Javier.

```text
POST /ingest/{book_id}   ──► GutenbergSource / SampleDataSource ──► GutenbergBook.split
                                                                         │
       datalake/ranges/001000-001999/1342.body.txt.<uuid>.tmp ──rename──► 1342.body.txt
       datalake/ranges/001000-001999/1342.header.txt.<uuid>.tmp ─rename─► 1342.header.txt
                                                                         │
                         datalake/ingested_books.txt ◄── append + fsync ─┘ ──► 200 "downloaded"
```

## API

Internal API, consumed by the Control Module. Full contract in
[`docs/api/openapi.yaml`](../docs/api/openapi.yaml) (tag *Internal: Ingestion*); runnable
examples in [`docs/api/examples/ingestion.http`](../docs/api/examples/ingestion.http).

| Method | Route | Success | Errors |
|--------|-------|---------|--------|
| POST | `/ingest/{book_id}` | 200 `{"book_id", "status": "downloaded" \| "already_available", "path"}` | 400 `invalid_parameter`, 404 `not_found`, 422 `invalid_book`, 502 `upstream_error`, 500 |
| GET | `/ingest/status/{book_id}` | 200 `{"book_id", "status": "available" \| "not_available"}` | 400, 500 |
| GET | `/ingest/list` | 200 `{"count", "books": [...]}` (ascending) | 500 |
| GET | `/status` | 200 health check | — |

`POST /ingest/{book_id}` is **synchronous**: `200` means both files are completely written
and the book is recorded. 404 and 422 are permanent; 502 is transient and safe to retry.

## Design

- **Completion marker.** Each file is written to a temporary name, forced to disk and renamed
  atomically. The body is renamed first and the header last, so a book whose header exists is
  complete. The Indexing Service, which only checks that both files exist, never reads a
  half-written book, and its rebuild ignores the `.tmp` files.
- **Persistent record.** After the renames the id is appended to `datalake/ingested_books.txt`
  (with fsync). Only then does `/ingest/status` answer `available`.
- **Idempotency.** A second request for an available book answers `already_available`
  without downloading it again.
- **Concurrency.** Each request runs in a Javalin (Jetty) thread; different books are
  ingested in parallel. Requests for the same book are serialized by a per-book lock, so the
  book is downloaded once. This lock only protects the threads of one process. With several
  replicas sharing the datalake, two of them could download the same book at the same time;
  the atomic renames still leave one complete copy, and `/ingest/status` and `/ingest/list`
  also look at the files, so every replica sees the books stored by the others. To avoid the
  duplicated download, the Control Module would send each book id to a fixed replica
  (e.g. `book_id % replicas`).
- **Recovery.** At start-up the service deletes leftover `.tmp` files (downloads interrupted
  by a crash) and records complete books that were renamed but not recorded yet.
- **Timeouts.** One download waits at most `GUTENBERG_TIMEOUT_SECONDS` (30 s), less than the
  60 s the Control Module waits, so a slow Gutenberg becomes a 502 instead of a caller timeout.

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `PORT` | `7001` | Port of the service |
| `DATALAKE_PATH` | `datalake` | Root of the datalake (must be the same as Indexing) |
| `INGESTION_THREADS` | `200` | Maximum request threads (Jetty `QueuedThreadPool`, minimum 8) |
| `SAMPLE_DATA` | *(empty)* | Folder with `pg{id}.txt`; if set, books are read from it instead of Gutenberg |
| `GUTENBERG_URL` | `https://www.gutenberg.org` | Base URL of Project Gutenberg |
| `GUTENBERG_TIMEOUT_SECONDS` | `30` | Timeout of one download |

## Run

From the repository root, after `./mvnw clean package`:

```bash
# Offline, with the five sample books
SAMPLE_DATA=sample_data java -jar ingestion-service/target/ingestion-service-1.0.0.jar
# Windows PowerShell
$env:SAMPLE_DATA="sample_data"; java -jar ingestion-service/target/ingestion-service-1.0.0.jar

curl -X POST http://localhost:7001/ingest/1342
```

## Tests and benchmarks

- `IngestionServiceTest`: datalake layout, idempotency, 8 concurrent requests for the same
  book (one download), 404 and 422, crash recovery.
- `IngestionApiTest`: the HTTP contract (status codes, JSON fields, error messages).
- `benchmark/IngestionBenchmark` (JMH, books from memory, no network): split of a book,
  ingestion of new books with 1, 2 and 4 threads, idempotent repeat and status lookup.

```bash
./mvnw -q -pl ingestion-service -am test-compile exec:exec \
    -Dbenchmark.args="IngestionBenchmark -rf csv -rff ingestion-service/jmh-results.csv"
```
