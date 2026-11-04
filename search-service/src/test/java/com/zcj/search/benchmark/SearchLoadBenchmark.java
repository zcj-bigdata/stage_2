package com.zcj.search.benchmark;

import com.zcj.search.SampleDataSeeder;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Integration (load) benchmark of the Search Service on a single machine.
 *
 * <p>The service runs as a separate process started from its executable jar, exactly as in
 * the deployment. A pool of client threads sends a fixed mix of queries for a fixed time.
 * One factor is varied at a time:</p>
 * <ul>
 *   <li><b>client concurrency</b>: number of simultaneous clients (list {@code --clients});</li>
 *   <li><b>server request threads</b>: {@code SEARCH_THREADS} of the service (list {@code --threads}).</li>
 * </ul>
 * <p>For every configuration there is a warm-up period that is discarded and then
 * {@code --reps} measured runs. For each run it records throughput, latency (mean, p50,
 * p95, p99), errors, CPU used by the service process and its resident memory. The summary
 * reports the mean and standard deviation across runs.</p>
 *
 * <p>Run from the repository root, after {@code ./mvnw package}:</p>
 * <pre>
 *   ./mvnw -q -pl search-service -am test-compile exec:exec -Dbenchmark.args="--books 200 --reps 5"
 * </pre>
 * <p>Results: {@code benchmarks/results/search_load_runs_<reuse|no_reuse>.csv} (one row per
 * run) and {@code search_load_summary_<reuse|no_reuse>.csv} (mean and std per configuration).
 * Use {@code --reuse-connections false} to measure the version that opens a database
 * connection for every request.</p>
 */
public class SearchLoadBenchmark {

    /** Fixed, reproducible query mix: single words, AND, filters, pagination, no matches, heavy query. */
    static final List<String> WORKLOAD = List.of(
            "/search?q=darcy",
            "/search?q=the",
            "/search?q=monster%20creature",
            "/search?q=sherlock%20holmes",
            "/search?q=love&author=austen",
            "/search?q=the&language=en&limit=20",
            "/search?q=the&offset=40&limit=20",
            "/search?q=the&year=1998",
            "/search?q=zzzzqx",
            "/search?q=it%20was%20the%20best%20of%20times");

    // ----------------------------------------------------------------- options

    private int[] clients = {1, 2, 4, 8, 16, 32, 64};
    private int[] threads = {8, 32, 200};
    private boolean reuseConnections = true;
    private int books = 200;
    private int reps = 5;
    private int warmupSeconds = 5;
    private int runSeconds = 10;
    private int port = 7103;
    private String heap = "1g";
    private Path jar = firstExisting("search-service/target/search-service-1.0.0.jar", "target/search-service-1.0.0.jar");
    private Path sampleData = firstExisting("sample_data", "../sample_data");
    private Path results = Path.of("benchmarks", "results");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        SearchLoadBenchmark benchmark = new SearchLoadBenchmark();
        benchmark.parse(args);
        benchmark.run();
    }

    private void parse(String[] args) {
        for (int i = 0; i + 1 < args.length; i += 2) {
            String value = args[i + 1];
            switch (args[i]) {
                case "--clients" -> clients = ints(value);
                case "--threads" -> threads = ints(value);
                case "--reuse-connections" -> reuseConnections = Boolean.parseBoolean(value);
                case "--books" -> books = Integer.parseInt(value);
                case "--reps" -> reps = Integer.parseInt(value);
                case "--warmup" -> warmupSeconds = Integer.parseInt(value);
                case "--duration" -> runSeconds = Integer.parseInt(value);
                case "--port" -> port = Integer.parseInt(value);
                case "--heap" -> heap = value;
                case "--jar" -> jar = Path.of(value);
                case "--sample" -> sampleData = Path.of(value);
                case "--results" -> results = Path.of(value);
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
    }

    // ----------------------------------------------------------------- main loop

    private void run() throws Exception {
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Service jar not found: " + jar.toAbsolutePath() + " (run ./mvnw package first)");
        }
        Files.createDirectories(results);
        Path db = Files.createTempDirectory("search-load").resolve("search.db");
        System.out.printf("Seeding %d books into %s ...%n", books, db);
        SampleDataSeeder.seedSynthetic(sampleData, db.toString(), books);

        printHost();

        try (PrintWriter runsCsv = new PrintWriter(Files.newBufferedWriter(results.resolve("search_load_runs_" + tag() + ".csv")));
             PrintWriter summaryCsv = new PrintWriter(Files.newBufferedWriter(results.resolve("search_load_summary_" + tag() + ".csv")))) {

            runsCsv.println("reuse_connections,server_threads,clients,run,requests,errors,throughput_rps,"
                    + "mean_ms,p50_ms,p95_ms,p99_ms,cpu_percent_host,rss_mb");
            summaryCsv.println("reuse_connections,server_threads,clients,runs,throughput_mean,throughput_std,"
                    + "mean_ms_mean,mean_ms_std,p95_ms_mean,p95_ms_std,p99_ms_mean,p99_ms_std,"
                    + "cpu_percent_mean,cpu_percent_std,rss_mb_mean,errors_total");

            for (int serverThreads : threads) {
                Process server = startServer(db, serverThreads);
                try {
                    for (int c : clients) {
                        measureConfiguration(server, serverThreads, c, runsCsv, summaryCsv);
                    }
                } finally {
                    server.destroy();
                    server.waitFor();
                }
            }
        }
        System.out.println("Results written to " + results.toAbsolutePath());
    }

    private void measureConfiguration(Process server, int serverThreads, int clientCount,
                                      PrintWriter runsCsv, PrintWriter summaryCsv) throws Exception {
        load(clientCount, warmupSeconds, server);  // warm-up: JIT, caches; discarded

        List<RunResult> runs = new ArrayList<>();
        for (int r = 1; r <= reps; r++) {
            RunResult result = load(clientCount, runSeconds, server);
            runs.add(result);
            runsCsv.printf(Locale.ROOT, "%b,%d,%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%.2f,%.1f%n",
                    reuseConnections, serverThreads, clientCount, r, result.requests, result.errors, result.throughput,
                    result.meanMs, result.p50Ms, result.p95Ms, result.p99Ms, result.cpuPercent, result.rssMb);
            runsCsv.flush();
        }

        double[] tp = runs.stream().mapToDouble(x -> x.throughput).toArray();
        double[] mean = runs.stream().mapToDouble(x -> x.meanMs).toArray();
        double[] p95 = runs.stream().mapToDouble(x -> x.p95Ms).toArray();
        double[] p99 = runs.stream().mapToDouble(x -> x.p99Ms).toArray();
        double[] cpu = runs.stream().mapToDouble(x -> x.cpuPercent).toArray();
        double[] rss = runs.stream().mapToDouble(x -> x.rssMb).toArray();
        long errors = runs.stream().mapToLong(x -> x.errors).sum();

        summaryCsv.printf(Locale.ROOT, "%b,%d,%d,%d,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%.2f,%.1f,%d%n",
                reuseConnections, serverThreads, clientCount, reps, avg(tp), std(tp), avg(mean), std(mean),
                avg(p95), std(p95), avg(p99), std(p99), avg(cpu), std(cpu), avg(rss), errors);
        summaryCsv.flush();

        System.out.printf(Locale.ROOT,
                "threads=%-4d clients=%-3d  %8.1f ± %6.1f req/s   mean %7.2f ms   p95 %7.2f ± %6.2f ms   cpu %5.1f%%   rss %6.1f MB   errors %d%n",
                serverThreads, clientCount, avg(tp), std(tp), avg(mean), avg(p95), std(p95), avg(cpu), avg(rss), errors);
    }

    // ----------------------------------------------------------------- one run

    private record RunResult(long requests, long errors, double throughput, double meanMs,
                             double p50Ms, double p95Ms, double p99Ms, double cpuPercent, double rssMb) {
    }

    /** Growable buffer of latencies (nanoseconds) owned by a single client thread. */
    private static final class Latencies {
        long[] values = new long[16_384];
        int size;

        void add(long nanos) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = nanos;
        }
    }

    /** Runs {@code clientCount} closed-loop clients for {@code seconds}. */
    private RunResult load(int clientCount, int seconds, Process server) throws Exception {
        String base = "http://localhost:" + port;
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicLong errors = new AtomicLong();
        List<Latencies> latencies = new ArrayList<>();  // one buffer per client, no sharing
        CountDownLatch started = new CountDownLatch(clientCount);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            Latencies buffer = new Latencies();
            latencies.add(buffer);
            int offset = i;  // clients start at different queries of the mix
            Thread t = new Thread(() -> {
                started.countDown();
                int q = offset;
                while (!stop.get()) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(base + WORKLOAD.get(q % WORKLOAD.size())))
                            .timeout(Duration.ofSeconds(30)).build();
                    long t0 = System.nanoTime();
                    try {
                        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                        if (response.statusCode() != 200) {
                            errors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    buffer.add(System.nanoTime() - t0);
                    q++;
                }
            });
            workers.add(t);
        }

        long cpuBefore = cpuNanos(server);
        workers.forEach(Thread::start);
        started.await();
        long wallStart = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread t : workers) {
            t.join();
        }
        long wallNanos = System.nanoTime() - wallStart;
        long cpuAfter = cpuNanos(server);

        int total = latencies.stream().mapToInt(l -> l.size).sum();
        long[] all = new long[total];
        int pos = 0;
        for (Latencies l : latencies) {
            System.arraycopy(l.values, 0, all, pos, l.size);
            pos += l.size;
        }
        Arrays.sort(all);

        double meanMs = total == 0 ? 0 : Arrays.stream(all).average().orElse(0) / 1e6;
        int cores = Runtime.getRuntime().availableProcessors();
        double cpuPercent = cpuBefore < 0 || cpuAfter < 0 ? Double.NaN
                : 100.0 * (cpuAfter - cpuBefore) / ((double) wallNanos * cores);

        return new RunResult(total, errors.get(), total / (wallNanos / 1e9), meanMs,
                percentile(all, 50), percentile(all, 95), percentile(all, 99), cpuPercent, rssMb(server.pid()));
    }

    // ----------------------------------------------------------------- server process

    private Process startServer(Path db, int serverThreads) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xms" + heap, "-Xmx" + heap, "-jar", jar.toString());
        pb.environment().put("PORT", String.valueOf(port));
        pb.environment().put("DB_PATH", db.toString());
        pb.environment().put("SEARCH_THREADS", String.valueOf(serverThreads));
        pb.environment().put("SEARCH_REUSE_CONNECTIONS", String.valueOf(reuseConnections));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process server = pb.start();

        String url = "http://localhost:" + port + "/status";
        for (int i = 0; i < 100; i++) {
            try {
                HttpResponse<Void> r = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    System.out.printf("%nSearch Service started (pid %d, SEARCH_THREADS=%d, heap %s)%n",
                            server.pid(), serverThreads, heap);
                    return server;
                }
            } catch (IOException e) {
                // not ready yet
            }
            Thread.sleep(200);
        }
        server.destroy();
        throw new IllegalStateException("The Search Service did not start on port " + port);
    }

    /** CPU time consumed by the service process so far, or -1 if the OS does not report it. */
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

    private void printHost() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        long totalMem = ((com.sun.management.OperatingSystemMXBean) os).getTotalMemorySize();
        System.out.printf("Host: %s %s, %d logical CPUs, %.1f GB RAM, Java %s%n",
                os.getName(), os.getVersion(), os.getAvailableProcessors(), totalMem / 1e9,
                System.getProperty("java.version"));
        System.out.printf("Clients %s, server threads %s, %d runs of %d s after %d s of warm-up, %d books, reuse connections %b%n",
                Arrays.toString(clients), Arrays.toString(threads), reps, runSeconds, warmupSeconds, books, reuseConnections);
        System.out.println("Started " + LocalDateTime.now());
    }

    // ----------------------------------------------------------------- statistics

    private String tag() {
        return reuseConnections ? "reuse" : "no_reuse";
    }

    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))] / 1e6;
    }

    private static double avg(double[] values) {
        return Arrays.stream(values).average().orElse(Double.NaN);
    }

    /** Sample standard deviation. */
    private static double std(double[] values) {
        if (values.length < 2) {
            return 0;
        }
        double mean = avg(values);
        double sum = 0;
        for (double v : values) {
            sum += (v - mean) * (v - mean);
        }
        return Math.sqrt(sum / (values.length - 1));
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
