# Ingestion Service and Control Module benchmarks

Three benchmarks. The results in 2026-11-08 were run on a different laptop from the Indexing and Search benchmarks (see host.txt).

## 1. JMH: Ingestion Service (`IngestionBenchmark`)

Component level, without the network: the books come from memory (the five sample books),
so the results measure the work of the service, not the speed of Project Gutenberg.

- `splitBook`: `GutenbergBook.split` of *Pride and Prejudice* (pure CPU).
- `ingestNewBook_1thread` / `_2threads` / `_4threads`: complete ingestion of a new book
  (split, both files written with fsync and atomic rename, id recorded) with 1, 2 and 4
  client threads. Every call stores a different book id, so every call writes.
- `ingestAlreadyAvailable`: idempotent repeat of an available book (no write).
- `statusLookup`: `GET /ingest/status` of an available book.

## 2. JMH: Control Module bookkeeping (`ControlStateBenchmark`)

The work the Control Module does for every book besides the two HTTP calls, i.e. the part of
the coordination cost that does not depend on the services.

- `recordBook_1thread` / `_2threads` / `_4threads`: `markDownloaded` + `markIndexed` of a new
  book (two lines appended to the control files and forced to disk).
- `claimAndRelease`: a worker looking for the next pending book.
- `snapshot`: building `GET /control/status`.
- Parameter `books`: 1 000, 10 000 and 70 000 books already processed.

Both JMH benchmarks: average time per operation, 5 warm-up + 10 measurement iterations of
1 s, 2 forks (20 samples), 99.9% confidence interval reported by JMH.

## 3. Load benchmark of the pipeline (`PipelineLoadBenchmark`)

System level. For every number of Control Module workers (`CONTROL_WORKERS` = 1, 2, 4, 8)
it runs 5 times, each time on an empty datalake, datamart and control folder:

1. start the Ingestion Service and the Indexing Service from their jars (heap 1 GB), the
   Ingestion Service reading the books from a local folder;
2. start the Control Module with 100 book ids (the five sample books repeated with synthetic
   ids 1..100, as in the Search load benchmark);
3. wait until the 100 books are indexed, read `GET /control/metrics`, check
   `GET /index/status` and stop the three processes.

One extra warm-up run is discarded. Per run: books per second over the time the Control
Module spent working (`active_s`), average time of the ingestion call, of the indexing call
and of a complete book as seen by the Control Module, retries, CPU of each process (% of the
whole host during the run) and resident memory at the end. The summary file has the mean and
the sample standard deviation of the 5 runs.

`control_overhead_ms` = `avg_book_ms` − `avg_ingest_ms` − `avg_index_ms`: time per book spent
by the Control Module outside the two calls (bookkeeping and scheduling).

## How to reproduce

On Windows, from the repository root:

```bat
docs\benchmarks\ingestion-control\run_benchmarks.cmd 2026-11-08
```

It builds the jars and runs the three benchmarks (about 25–30 minutes). The results go to
`docs/benchmarks/ingestion-control/<argument>/`:

| File | Content |
|------|---------|
| `host.txt` | CPU, cores, RAM, OS, Java version and commit |
| `ingestion_jmh.csv` / `.log` | JMH results of the Ingestion Service |
| `control_jmh.csv` / `.log` | JMH results of the Control Module bookkeeping |
| `pipeline_load_runs.csv` | One row per run of the load benchmark |
| `pipeline_load_summary.csv` | Mean and standard deviation per number of workers |
| `pipeline_load.log` | Console output of the load benchmark |

The same commands on Linux or macOS:

```bash
./mvnw -q clean package -DskipTests
./mvnw -q -pl ingestion-service -am test-compile exec:exec \
    -Dbenchmark.args="IngestionBenchmark -f 2 -bm avgt -tu ms -rf csv -rff docs/benchmarks/ingestion-control/results/ingestion_jmh.csv"
./mvnw -q -pl control-module -am test-compile exec:exec \
    -Dbenchmark.args="ControlStateBenchmark -f 2 -bm avgt -tu us -rf csv -rff docs/benchmarks/ingestion-control/results/control_jmh.csv"
./mvnw -q -pl control-module -am test-compile exec:exec \
    -Dbenchmark.main=com.zcj.control.benchmark.PipelineLoadBenchmark \
    -Dbenchmark.args="--books 100 --workers 1,2,4,8 --reps 5 --results docs/benchmarks/ingestion-control/results"
```
