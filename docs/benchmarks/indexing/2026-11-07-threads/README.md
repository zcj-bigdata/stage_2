# Indexing Service JMH benchmarks — 1, 2 and 4 threads (2026-11-07)

`BookIndexerBenchmark` (indexing-service/src/test/java) measured with 1, 2 and 4 benchmark
threads. Each thread has its own temporary datalake and datamart, so the threads only share
the host (CPU, memory, disk).

| Item | Value |
|------|-------|
| Host | Laptop, Intel Core Ultra 7 155H (16 cores, 22 logical CPUs), 16 GB RAM, SSD, Windows 11, plugged in |
| Java | 21.0.5 (code compiled for Java 17) |
| Mode | Average time per operation (ms/op) |
| Iterations | 5 warm-up + 10 measurement iterations of 1 s, 2 forks (20 samples) |
| Error | 99.9% confidence interval reported by JMH (`Score Error (99.9%)` column) |

Benchmarks:

- `extractBookMetadata`: parse the header of one book.
- `tokenizeBookBody`: tokenize the body of one book.
- `updateOneBookIndex`: index one book in its own transaction.
- `indexFiveSampleBooks`: index the five sample books.

Files: `indexing_jmh_t1.csv`, `indexing_jmh_t2.csv`, `indexing_jmh_t4.csv` (JMH CSV output)
and the full console output in the `.log` files.

## How to reproduce

From the repository root:

```bash
./mvnw -q install -DskipTests -pl common -am
./mvnw -q -pl indexing-service test-compile exec:exec \
    -Dbenchmark.args="BookIndexerBenchmark -t 1 -rf csv -rff docs/benchmarks/indexing/2026-11-07-threads/indexing_jmh_t1.csv"
```

(and the same with `-t 2` and `-t 4`).

Absolute times depend on the machine: the earlier results in `indexing-service/jmh-results.json`
were measured on a different computer and must not be compared with these ones.
