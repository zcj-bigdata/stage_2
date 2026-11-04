# Search Service benchmarks — baseline (2026-11-04)

Baseline measurements of the Search Service before storing term frequencies in the
datamart: relevance (`hits`) is computed by counting the positions of each posting.

## Environment

| Item | Value |
|------|-------|
| Host | Laptop, Intel Core Ultra 7 155H (16 cores, 22 logical CPUs), 16 GB RAM, SSD |
| OS | Windows 11, plugged in |
| Java | 21.0.5 (code compiled for Java 17) |
| Code | branch `search`, commit `4651af5` |
| Datamart | 200 books (the 5 sample books repeated with synthetic ids), SQLite in WAL mode |

All processes (benchmark clients and service) run on the same host.

## JMH microbenchmarks (`SearchJmhBenchmark`)

Component level, no HTTP: query parsing and `SearchRepository.search`.

- Mode: average time per operation (ms/op).
- 5 warm-up iterations + 10 measurement iterations of 1 s, 2 forks (20 samples), heap 1 GB.
- Error: 99.9% confidence interval reported by JMH.
- `-t 1`: one thread; `-t 8`: eight threads sharing the repository, each with its own connection.

Files: `search_jmh_t1.csv`, `search_jmh_t8.csv` (and the full JMH output in the `.log` files).

## Load benchmark (`SearchLoadBenchmark`)

System level: the Search Service runs as a separate process from its jar (heap 1 GB) and
closed-loop clients send a fixed mix of 10 queries over HTTP.

- Client concurrency: 1, 2, 4, 8, 16, 32, 64.
- Server request threads (`SEARCH_THREADS`): 8, 32, 200.
- Connection handling: one reused connection per request thread (`reuse`) and one new
  connection per request (`no_reuse`, only with 200 threads).
- For every configuration: 5 s of warm-up (discarded) and 5 measured runs of 10 s.
- Per run: throughput, mean / p50 / p95 / p99 latency, errors, CPU of the service process
  (% of the whole host) and its resident memory.
- Summary files: mean and sample standard deviation across the 5 runs.

Files: `search_load_runs_*.csv` (one row per run), `search_load_summary_*.csv`
(mean and std per configuration), `search_load_*.log` (console output).

## How to reproduce

From the repository root, on the commit above:

```bash
./mvnw clean package -DskipTests
./mvnw -q -pl search-service -am test-compile exec:exec -Dbenchmark.main=org.openjdk.jmh.Main \
    -Dbenchmark.args="SearchJmhBenchmark -t 1 -rf csv -rff benchmarks/results/search_jmh_t1.csv"
./mvnw -q -pl search-service -am test-compile exec:exec -Dbenchmark.main=org.openjdk.jmh.Main \
    -Dbenchmark.args="SearchJmhBenchmark -t 8 -rf csv -rff benchmarks/results/search_jmh_t8.csv"
./mvnw -q -pl search-service -am test-compile exec:exec \
    -Dbenchmark.args="--books 200 --reps 5 --warmup 5 --duration 10 --threads 8,32,200 --clients 1,2,4,8,16,32,64 --reuse-connections true"
./mvnw -q -pl search-service -am test-compile exec:exec \
    -Dbenchmark.args="--books 200 --reps 5 --warmup 5 --duration 10 --threads 200 --clients 1,2,4,8,16,32,64 --reuse-connections false"
```

Note: the JMH CSV files of this run were produced with a Spanish locale (decimal comma)
and converted to decimal points; later runs write decimal points directly.
