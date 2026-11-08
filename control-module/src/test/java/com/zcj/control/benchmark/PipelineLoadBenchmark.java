package com.zcj.control.benchmark;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * Load benchmark of the whole ingestion → indexing pipeline, coordinated by the Control Module.
 *
 * <p>For every value of {@code --workers} (the {@code CONTROL_WORKERS} of the Control Module)
 * it runs {@code --reps} times the following experiment, each time on an empty datalake,
 * datamart and control folder:
 * <ol>
 *   <li>start the Ingestion Service and the Indexing Service from their jars, as separate
 *       processes (Ingestion reads the books from a local folder, so the network is not measured);</li>
 *   <li>start the Control Module with the list of {@code --books} book ids;</li>
 *   <li>wait until every book is indexed (or failed), then read {@code GET /control/metrics},
 *       check {@code GET /index/status} and stop the three processes.</li>
 * </ol>
 * The books are the five sample books repeated with synthetic ids 1..N, as in the Search
 * Service load benchmark.
 *
 * <p>Per run it records the throughput of the pipeline (books per second over the time the
 * Control Module spent working), the average time of the ingestion call, of the indexing call
 * and of a complete book as seen by the Control Module, the retries, the CPU used by each
 * process (% of the whole host during the run) and their resident memory at the end.
 *
 * <p>Run it from the repository root, after {@code ./mvnw clean package -DskipTests}:
 * <pre>
 *   ./mvnw -q -pl control-module -am test-compile exec:exec \
 *       -Dbenchmark.main=com.zcj.control.benchmark.PipelineLoadBenchmark \
 *       -Dbenchmark.args="--books 100 --workers 1,2,4,8 --reps 5"
 * </pre>
 * Results: {@code pipeline_load_runs.csv} (one row per run) and {@code pipeline_load_summary.csv}
 * (mean and sample standard deviation per number of workers) in {@code --results}.
 */
public class PipelineLoadBenchmark {

    static final List<Integer> SAMPLE_BOOKS = List.of(11, 84, 98, 1342, 1661);

    // ----------------------------------------------------------------- options

    private int books = 100;
    private int[] workers = {1, 2, 4, 8};
    private int reps = 5;
    private int warmupRuns = 1;
    private int timeoutSeconds = 600;
    private int basePort = 17000;
    private String heap = "1G";
    private Path sampleData = firstExisting("sample_data", "../sample_data");
    private Path ingestionJar = firstExisting("ingestion-service/target/ingestion-service-1.0.0.jar",
            "../ingestion-service/target/ingestion-service-1.0.0.jar");
    private Path indexingJar = firstExisting("indexing-service/target/indexing-service-1.0.0.jar",
            "../indexing-service/target/indexing-service-1.0.0.jar");
    private Path controlJar = firstExisting("control-module/target/control-module-1.0.0.jar",
            "../control-module/target/control-module-1.0.0.jar");
    private Path results = Path.of("benchmarks", "results");

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final int cores = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Exception {
        PipelineLoadBenchmark benchmark = new PipelineLoadBenchmark();
        benchmark.parse(args);
        benchmark.run();
    }

    void parse(String[] args) {
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + args[i]);
            }
            String value = args[i + 1];
            switch (args[i]) {
                case "--books" -> books = Integer.parseInt(value);
                case "--workers" -> workers = ints(value);
                case "--reps" -> reps = Integer.parseInt(value);
                case "--warmup-runs" -> warmupRuns = Integer.parseInt(value);
                case "--timeout" -> timeoutSeconds = Integer.parseInt(value);
                case "--base-port" -> basePort = Integer.parseInt(value);
                case "--heap" -> heap = value;
                case "--sample-data" -> sampleData = Path.of(value);
                case "--ingestion-jar" -> ingestionJar = Path.of(value);
                case "--indexing-jar" -> indexingJar = Path.of(value);
                case "--control-jar" -> controlJar = Path.of(value);
                case "--results" -> results = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        if (books <= 0 || reps <= 0 || workers.length == 0) {
            throw new IllegalArgumentException("--books, --reps and --workers must be positive");
        }
    }

    // ----------------------------------------------------------------- main loop

    private void run() throws Exception {
        for (Path jar : List.of(ingestionJar, indexingJar, controlJar)) {
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(jar + " not found: run ./mvnw clean package -DskipTests first");
            }
        }
        Files.createDirectories(results);
        printHost();

        Path booksDir = Files.createTempDirectory("pipeline-books");
        try {
            createBooks(booksDir);
            for (int i = 1; i <= warmupRuns; i++) {
                System.out.printf("Warm-up run %d (discarded)%n", i);
                runOnce(booksDir, workers[0], 0);
            }
            try (PrintWriter runsCsv = new PrintWriter(Files.newBufferedWriter(results.resolve("pipeline_load_runs.csv")));
                 PrintWriter summaryCsv = new PrintWriter(Files.newBufferedWriter(results.resolve("pipeline_load_summary.csv")))) {
                runsCsv.println("workers,run,books,indexed,failed,retries,active_s,books_per_second,"
                        + "avg_ingest_ms,avg_index_ms,avg_book_ms,control_overhead_ms,wall_s,"
                        + "cpu_ingestion_pct,cpu_indexing_pct,cpu_control_pct,"
                        + "rss_ingestion_mb,rss_indexing_mb,rss_control_mb");
                summaryCsv.println("workers,runs,books_per_second_mean,books_per_second_std,"
                        + "avg_ingest_ms_mean,avg_ingest_ms_std,avg_index_ms_mean,avg_index_ms_std,"
                        + "avg_book_ms_mean,avg_book_ms_std,control_overhead_ms_mean,"
                        + "cpu_ingestion_pct_mean,cpu_indexing_pct_mean,cpu_control_pct_mean,"
                        + "rss_ingestion_mb_mean,rss_indexing_mb_mean,rss_control_mb_mean,"
                        + "retries_total,failed_total");
                for (int w : workers) {
                    List<Result> runs = new ArrayList<>();
                    for (int r = 1; r <= reps; r++) {
                        Result result = runOnce(booksDir, w, r);
                        runs.add(result);
                        runsCsv.printf(Locale.ROOT,
                                "%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f,%.3f,%.2f,%.2f,%.2f,%.1f,%.1f,%.1f%n",
                                w, r, books, result.indexed, result.failed, result.retries, result.activeS,
                                result.booksPerSecond, result.avgIngestMs, result.avgIndexMs, result.avgBookMs,
                                result.overheadMs(), result.wallS, result.cpuIngestion, result.cpuIndexing,
                                result.cpuControl, result.rssIngestion, result.rssIndexing, result.rssControl);
                        runsCsv.flush();
                    }
                    summarize(w, runs, summaryCsv);
                }
            }
        } finally {
            deleteQuietly(booksDir);
        }
        System.out.println("Results written to " + results.toAbsolutePath().normalize());
        System.out.println("Finished " + LocalDateTime.now());
    }

    private void summarize(int w, List<Result> runs, PrintWriter summaryCsv) {
        double[] bps = runs.stream().mapToDouble(x -> x.booksPerSecond).toArray();
        double[] ingest = runs.stream().mapToDouble(x -> x.avgIngestMs).toArray();
        double[] index = runs.stream().mapToDouble(x -> x.avgIndexMs).toArray();
        double[] book = runs.stream().mapToDouble(x -> x.avgBookMs).toArray();
        double[] overhead = runs.stream().mapToDouble(Result::overheadMs).toArray();
        long retries = runs.stream().mapToLong(x -> x.retries).sum();
        long failed = runs.stream().mapToLong(x -> x.failed).sum();
        summaryCsv.printf(Locale.ROOT,
                "%d,%d,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.1f,%.1f,%.1f,%d,%d%n",
                w, runs.size(), avg(bps), std(bps), avg(ingest), std(ingest), avg(index), std(index),
                avg(book), std(book), avg(overhead),
                avg(runs.stream().mapToDouble(x -> x.cpuIngestion).toArray()),
                avg(runs.stream().mapToDouble(x -> x.cpuIndexing).toArray()),
                avg(runs.stream().mapToDouble(x -> x.cpuControl).toArray()),
                avg(runs.stream().mapToDouble(x -> x.rssIngestion).toArray()),
                avg(runs.stream().mapToDouble(x -> x.rssIndexing).toArray()),
                avg(runs.stream().mapToDouble(x -> x.rssControl).toArray()),
                retries, failed);
        summaryCsv.flush();
        System.out.printf(Locale.ROOT,
                "workers=%-2d  %6.2f +/- %5.2f books/s   ingest %7.1f ms   index %7.1f ms   book %7.1f ms   retries %d   failed %d%n",
                w, avg(bps), std(bps), avg(ingest), avg(index), avg(book), retries, failed);
    }

    // ----------------------------------------------------------------- one run

    record Result(int indexed, int failed, long retries, double activeS, double booksPerSecond,
                  double avgIngestMs, double avgIndexMs, double avgBookMs, double wallS,
                  double cpuIngestion, double cpuIndexing, double cpuControl,
                  double rssIngestion, double rssIndexing, double rssControl) {

        /** Time per book spent outside the two service calls (bookkeeping and scheduling). */
        double overheadMs() {
            return avgBookMs - avgIngestMs - avgIndexMs;
        }
    }

    private Result runOnce(Path booksDir, int workerCount, int run) throws Exception {
        Path runDir = Files.createTempDirectory("pipeline-run");
        int controlPort = basePort;
        int ingestionPort = basePort + 1;
        int indexingPort = basePort + 2;
        String ingestionUrl = "http://localhost:" + ingestionPort;
        String indexingUrl = "http://localhost:" + indexingPort;
        String controlUrl = "http://localhost:" + controlPort;

        Process ingestion = null;
        Process indexing = null;
        Process control = null;
        boolean ok = false;
        try {
            ingestion = start(ingestionJar, runDir.resolve("ingestion.log"),
                    "PORT", String.valueOf(ingestionPort),
                    "DATALAKE_PATH", runDir.resolve("datalake").toString(),
                    "SAMPLE_DATA", booksDir.toString());
            indexing = start(indexingJar, runDir.resolve("indexing.log"),
                    "PORT", String.valueOf(indexingPort),
                    "DATALAKE_PATH", runDir.resolve("datalake").toString(),
                    "DB_PATH", runDir.resolve("datamarts").resolve("index.db").toString());
            waitUntilUp(ingestionUrl, "Ingestion Service", ingestion);
            waitUntilUp(indexingUrl, "Indexing Service", indexing);

            long ingestionCpu0 = cpuNanos(ingestion);
            long indexingCpu0 = cpuNanos(indexing);
            long t0 = System.nanoTime();
            control = start(controlJar, runDir.resolve("control.log"),
                    "PORT", String.valueOf(controlPort),
                    "INGESTION_URL", ingestionUrl,
                    "INDEXING_URL", indexingUrl,
                    "CONTROL_PATH", runDir.resolve("control").toString(),
                    "CONTROL_BOOKS", bookIds(),
                    "CONTROL_MAX_BOOKS", "0",
                    "CONTROL_WORKERS", String.valueOf(workerCount),
                    "CONTROL_INTERVAL_MS", "100");

            // Wait until every book is indexed or failed
            long deadline = t0 + Duration.ofSeconds(timeoutSeconds).toNanos();
            JsonObject status = null;
            while (System.nanoTime() < deadline) {
                if (!control.isAlive()) {
                    throw new IllegalStateException("The Control Module stopped; see " + runDir.resolve("control.log"));
                }
                status = getJson(controlUrl + "/control/status");
                if (status != null && status.get("indexed").getAsInt() + status.get("failed").getAsInt() >= books) {
                    break;
                }
                Thread.sleep(100);
            }
            long wallNanos = System.nanoTime() - t0;
            if (status == null || status.get("indexed").getAsInt() + status.get("failed").getAsInt() < books) {
                throw new IllegalStateException("Timeout after " + timeoutSeconds + " s; logs in " + runDir);
            }

            double cpuIngestion = cpuPercent(cpuNanos(ingestion) - ingestionCpu0, wallNanos);
            double cpuIndexing = cpuPercent(cpuNanos(indexing) - indexingCpu0, wallNanos);
            double cpuControl = cpuPercent(cpuNanos(control), wallNanos);
            double rssIngestion = rssMb(ingestion.pid());
            double rssIndexing = rssMb(indexing.pid());
            double rssControl = rssMb(control.pid());

            JsonObject metrics = waitForFinalMetrics(controlUrl);
            JsonObject indexStatus = getJson(indexingUrl + "/index/status");
            int indexed = status.get("indexed").getAsInt();
            int failed = status.get("failed").getAsInt();
            if (indexStatus != null && indexStatus.has("books_indexed")
                    && indexStatus.get("books_indexed").getAsInt() != indexed) {
                System.out.printf("  WARNING: Control counts %d indexed books, Indexing Service reports %d%n",
                        indexed, indexStatus.get("books_indexed").getAsInt());
            }

            Result result = new Result(indexed, failed,
                    metrics.get("retries").getAsLong(),
                    metrics.get("active_s").getAsDouble(),
                    metrics.get("books_per_second").getAsDouble(),
                    metrics.get("avg_ingest_ms").getAsDouble(),
                    metrics.get("avg_index_ms").getAsDouble(),
                    metrics.get("avg_book_ms").getAsDouble(),
                    wallNanos / 1e9, cpuIngestion, cpuIndexing, cpuControl,
                    rssIngestion, rssIndexing, rssControl);
            if (run > 0) {
                System.out.printf(Locale.ROOT,
                        "  workers=%d run=%d  %d books in %.1f s  %.2f books/s  ingest %.1f ms  index %.1f ms  retries %d%n",
                        workerCount, run, indexed, result.activeS(), result.booksPerSecond(),
                        result.avgIngestMs(), result.avgIndexMs(), result.retries());
            }
            ok = true;
            return result;
        } finally {
            stop(control);
            stop(indexing);
            stop(ingestion);
            if (ok) {
                deleteQuietly(runDir);
            } else {
                System.out.println("  Run failed; logs kept in " + runDir);
            }
        }
    }

    /** /control/metrics after the workers have stopped (running = false). */
    private JsonObject waitForFinalMetrics(String controlUrl) throws InterruptedException {
        JsonObject metrics = null;
        for (int i = 0; i < 100; i++) {
            metrics = getJson(controlUrl + "/control/metrics");
            if (metrics != null && !metrics.get("running").getAsBoolean()) {
                return metrics;
            }
            Thread.sleep(100);
        }
        if (metrics == null) {
            throw new IllegalStateException("GET /control/metrics did not answer");
        }
        return metrics;
    }

    // ----------------------------------------------------------------- processes

    private Process start(Path jar, Path log, String... env) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xms" + heap, "-Xmx" + heap, "-jar", jar.toString());
        for (int i = 0; i + 1 < env.length; i += 2) {
            pb.environment().put(env[i], env[i + 1]);
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        return pb.start();
    }

    private void waitUntilUp(String url, String name, Process process) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " stopped during start-up (port in use?)");
            }
            if (getJson(url + "/status") != null) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(name + " did not start at " + url);
    }

    private static void stop(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        process.destroy();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** JSON object of a 200 answer, or null if the service does not answer (yet). */
    private JsonObject getJson(String url) {
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return null;
            }
            JsonElement element = JsonParser.parseString(r.body());
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private double cpuPercent(long cpuNanos, long wallNanos) {
        return cpuNanos < 0 ? Double.NaN : 100.0 * cpuNanos / ((double) wallNanos * cores);
    }

    /** CPU time consumed by a process so far, or -1 if the OS does not report it. */
    private static long cpuNanos(Process process) {
        return process.toHandle().info().totalCpuDuration().map(Duration::toNanos).orElse(-1L);
    }

    /** Resident memory (working set) of a process in MB, using the OS tools. */
    private static double rssMb(long pid) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String[] cmd = windows
                ? new String[]{"powershell", "-NoProfile", "-Command", "(Get-Process -Id " + pid + ").WorkingSet64"}
                : new String[]{"ps", "-o", "rss=", "-p", String.valueOf(pid)};
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                out = reader.readLine();
            }
            p.waitFor();
            double value = Double.parseDouble(out.trim());
            return windows ? value / (1024 * 1024) : value / 1024;  // bytes or KB
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ----------------------------------------------------------------- data

    /** pg1.txt ... pgN.txt: the five sample books repeated with synthetic ids. */
    void createBooks(Path folder) throws IOException {
        for (int id = 1; id <= books; id++) {
            int sample = SAMPLE_BOOKS.get((id - 1) % SAMPLE_BOOKS.size());
            Files.copy(sampleData.resolve("pg" + sample + ".txt"), folder.resolve("pg" + id + ".txt"));
        }
    }

    String bookIds() {
        StringJoiner ids = new StringJoiner(",");
        for (int id = 1; id <= books; id++) {
            ids.add(String.valueOf(id));
        }
        return ids.toString();
    }

    private void printHost() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        long totalMem = ((com.sun.management.OperatingSystemMXBean) os).getTotalMemorySize();
        System.out.printf("Host: %s %s, %d logical CPUs, %.1f GB RAM, Java %s%n",
                os.getName(), os.getVersion(), os.getAvailableProcessors(), totalMem / 1e9,
                System.getProperty("java.version"));
        System.out.printf("%d books, workers %s, %d runs per configuration after %d warm-up run(s), heap %s%n",
                books, Arrays.toString(workers), reps, warmupRuns, heap);
        System.out.println("Started " + LocalDateTime.now());
    }

    private static void deleteQuietly(Path folder) {
        // On Windows a file may stay locked for a moment after its process ends
        for (int attempt = 0; attempt < 5; attempt++) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        System.out.println("  Could not delete " + folder + " (delete it by hand)");
    }

    // ----------------------------------------------------------------- statistics

    static double avg(double[] values) {
        return Arrays.stream(values).filter(v -> !Double.isNaN(v)).average().orElse(Double.NaN);
    }

    /** Sample standard deviation. */
    static double std(double[] values) {
        double[] v = Arrays.stream(values).filter(x -> !Double.isNaN(x)).toArray();
        if (v.length < 2) {
            return 0;
        }
        double mean = avg(v);
        double sum = 0;
        for (double x : v) {
            sum += (x - mean) * (x - mean);
        }
        return Math.sqrt(sum / (v.length - 1));
    }

    private static int[] ints(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }

    private static Path firstExisting(String... candidates) {
        for (String c : candidates) {
            if (Files.exists(Path.of(c))) {
                return Path.of(c);
            }
        }
        return Path.of(candidates[0]);
    }
}
