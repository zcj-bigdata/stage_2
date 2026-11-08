# Control Module

Coordinates the pipeline with **classical orchestration**: it calls the internal APIs of the
Ingestion and Indexing services and records the progress in control files, as the control
layer of Stage 1 did with function calls. Port **7000**. Owner: Javier.

```text
          ┌──────────────── worker thread (CONTROL_WORKERS) ────────────────┐
          │ 1. pending book (downloaded, not indexed)?  ──yes──► step 4      │
          │ 2. claim a new book (CONTROL_BOOKS or random id)                 │
          │ 3. POST /ingest/{id} ──200──► append to downloaded_books.txt     │
          │ 4. POST /index/update/{id} ──200──► append to indexed_books.txt  │
          └──────────────────────────────────────────────────────────────────┘
                 404/422/400 ──► failed_books.txt (never retried)
         timeout/5xx/409 ──► retry with backoff, then leave it for a later step
```

## Workflow guarantees

| Requirement | How it is met |
|-------------|---------------|
| A book is indexed only after its ingestion has completed | Indexing is called only after `POST /ingest/{id}` answers 200, which by contract means both files are written and recorded |
| A book is searchable only when its index update is complete | The book counts as indexed only after `POST /index/update/{id}` answers 200 (transaction committed) |
| No duplicated work between workers | A book is *claimed* in memory before it is processed; no other worker can take it until it is released |
| No duplicates after retries | Ingestion answers `already_available`; indexing replaces the rows of the book |
| Crash between a call and its record | The state can only be *behind* the services (lines are appended after the 200). On restart the step is repeated, which is safe because both services are idempotent |
| Crash in the middle of an append | A cut last line is ignored and terminated before the next append |
| Ingestion timeout | Before retrying, `GET /ingest/status/{id}` checks whether the download finished anyway |
| Rebuild in progress | `409 rebuild_in_progress` is retried with backoff |
| Services started in any order | The loop waits until `GET /status` of Ingestion and Indexing answer 200 |

## API

Operational endpoints (consumer: operators):

| Method | Route | Response |
|--------|-------|----------|
| GET | `/status` | Health check |
| GET | `/control/status` | `{"downloaded", "indexed", "failed", "pending"}` |
| GET | `/control/metrics` | Coordination cost since start-up: `books_indexed`, `books_per_second` (over `active_s`, the time spent working), `avg_ingest_ms`, `avg_index_ms`, `avg_book_ms`, `retries`, `backoff_wait_ms`, `running`, `workers` |

Examples in [`docs/api/examples/control.http`](../docs/api/examples/control.http).

## Configuration

| Variable | Default | Meaning |
|----------|---------|---------|
| `PORT` | `7000` | Port of the module |
| `INGESTION_URL` / `INDEXING_URL` | `http://localhost:7001` / `7002` | Services to call |
| `CONTROL_PATH` | `control` | Folder of the control files |
| `CONTROL_BOOKS` | *(empty)* | Ids to process (`11,84,1342`), `sample` for the five sample books, or empty for random ids |
| `CONTROL_TOTAL_BOOKS` | `70000` | Highest random id |
| `CONTROL_MAX_BOOKS` | `10` | Stop after this many books are indexed in this run (`0` = no limit) |
| `CONTROL_WORKERS` | `1` | Worker threads; each processes a different book |
| `CONTROL_INTERVAL_MS` | `1000` | Pause after a step with nothing to do or a transient failure |
| `CONTROL_MAX_ATTEMPTS` | `3` | Attempts of one call before leaving the book for later |
| `CONTROL_BACKOFF_MS` | `1000` | Wait before the 2nd attempt; doubled each time (1 s, 2 s, 4 s…) |
| `INGEST_TIMEOUT_SECONDS` | `60` | Timeout of `POST /ingest/{id}` |
| `INDEX_TIMEOUT_SECONDS` | `120` | Timeout of `POST /index/update/{id}` |

## Run the whole pipeline with the sample books

Four terminals, all from the repository root, after `./mvnw clean package`:

```bash
SAMPLE_DATA=sample_data java -jar ingestion-service/target/ingestion-service-1.0.0.jar
java -jar indexing-service/target/indexing-service-1.0.0.jar
java -jar search-service/target/search-service-1.0.0.jar
CONTROL_BOOKS=sample java -jar control-module/target/control-module-1.0.0.jar

curl http://localhost:7000/control/status      # {"downloaded":5,"indexed":5,"failed":0,"pending":0}
curl "http://localhost:7003/search?q=darcy"    # book 1342, hits 432
```

With random ids and Project Gutenberg (no `SAMPLE_DATA`, no `CONTROL_BOOKS`), it indexes 10
books and stops; `CONTROL_MAX_BOOKS=0` keeps it running.

## Limits of this design (single machine, Stage 2)

- **One Control Module process.** Claims live in memory and the control files are plain
  local files, so two Control Module processes would duplicate work. Several *threads* are
  supported (`CONTROL_WORKERS`). In Stage 3 the state should move to a shared store with
  atomic claims (a database row per book, or a message broker with acknowledgements).
- **Indexing is a single writer.** SQLite accepts one write transaction at a time, so more
  workers mostly increase waiting on the datamart (and `database is locked` retries) once
  indexing is the slowest step.
- **localhost URLs and local files.** In a cluster the URLs become service addresses and
  the datalake must be a shared or distributed file system.

## Tests and benchmarks

- `ControlStateTest`: control files survive a restart, cut lines, claims, configuration.
- `OrchestratorTest` (fake services on the JDK HTTP server): workflow order, 404 without
  indexing, retried 502, timeout followed by a status check, 409 during a rebuild, indexing
  down and later resumed, restart from the control files, 4 workers never sharing a book.
- `benchmark/ControlStateBenchmark` (JMH): bookkeeping per book with 1, 2 and 4 threads,
  finding a pending book and building `/control/status` with 1k, 10k and 70k processed books.

```bash
./mvnw -q -pl control-module -am test-compile exec:exec \
    -Dbenchmark.args="ControlStateBenchmark -rf csv -rff control-module/jmh-results.csv"
```

- `benchmark/PipelineLoadBenchmark`: load benchmark of the whole pipeline. It starts the
  Ingestion and Indexing services and the Control Module from their jars, processes 100 books
  with 1, 2, 4 and 8 workers (5 runs each) and records books per second, time per call,
  retries, CPU and memory of each process. All the benchmarks of this module and of the
  Ingestion Service run with one command:
  [`docs/benchmarks/ingestion-control/run_benchmarks.cmd`](../docs/benchmarks/ingestion-control/README.md).
