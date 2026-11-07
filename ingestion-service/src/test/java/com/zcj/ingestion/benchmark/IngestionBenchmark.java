package com.zcj.ingestion.benchmark;

import com.zcj.common.Datalake;
import com.zcj.common.GutenbergBook;
import com.zcj.ingestion.BookSource;
import com.zcj.ingestion.IngestionException;
import com.zcj.ingestion.IngestionService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * JMH microbenchmarks of the Ingestion Service, without the network: the books come from
 * memory (the five sample books), so the results measure the work of the service itself
 * (splitting header and body, writing both files with fsync and atomic renames, and
 * recording the book), not the speed of Project Gutenberg.
 *
 * <ul>
 *   <li>{@code splitBook}: GutenbergBook.split of Pride and Prejudice (pure CPU).</li>
 *   <li>{@code ingestNewBook_Nthreads}: complete ingestion of a new book with 1, 2 and 4
 *       client threads. Each call stores a different book id, so every call writes.</li>
 *   <li>{@code ingestAlreadyAvailable}: idempotent repeat, answered without writing.</li>
 *   <li>{@code statusLookup}: GET /ingest/status of an available book.</li>
 * </ul>
 *
 * A new, empty datalake is created in a temporary folder for every iteration and deleted
 * after it. Run it from the repository root:
 * <pre>
 *   ./mvnw -q -pl ingestion-service -am test-compile exec:exec \
 *       -Dbenchmark.args="IngestionBenchmark -rf csv -rff ingestion-service/jmh-results.csv"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms2G", "-Xmx2G"})
@State(Scope.Benchmark)
public class IngestionBenchmark {

    private static final List<Integer> SAMPLE_BOOKS = List.of(11, 84, 98, 1342, 1661);
    private static final int AVAILABLE_BOOK = 1;

    private String[] rawBooks;
    private String prideAndPrejudice;
    private Path workDir;
    private IngestionService service;
    private final AtomicInteger nextId = new AtomicInteger();

    @Setup(Level.Trial)
    public void loadSampleBooks() throws IOException {
        Path folder = sampleDataFolder();
        rawBooks = new String[SAMPLE_BOOKS.size()];
        for (int i = 0; i < SAMPLE_BOOKS.size(); i++) {
            rawBooks[i] = Files.readString(folder.resolve("pg" + SAMPLE_BOOKS.get(i) + ".txt"), StandardCharsets.UTF_8);
        }
        prideAndPrejudice = rawBooks[SAMPLE_BOOKS.indexOf(1342)];
    }

    @Setup(Level.Iteration)
    public void newDatalake() throws Exception {
        workDir = Files.createTempDirectory("ingestion-jmh");
        BookSource memory = new BookSource() {
            @Override
            public String fetch(int bookId) {
                return rawBooks[bookId % rawBooks.length];
            }

            @Override
            public String describe() {
                return "memory";
            }
        };
        service = new IngestionService(new Datalake(workDir.resolve("datalake")), memory);
        service.ingest(AVAILABLE_BOOK);
        nextId.set(AVAILABLE_BOOK + 1);
    }

    @TearDown(Level.Iteration)
    public void deleteDatalake() throws IOException {
        try (Stream<Path> files = Files.walk(workDir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Benchmark
    public GutenbergBook.Parts splitBook() {
        return GutenbergBook.split(prideAndPrejudice);
    }

    @Benchmark
    @Threads(1)
    public IngestionService.Result ingestNewBook_1thread() throws IngestionException, IOException {
        return service.ingest(nextId.getAndIncrement());
    }

    @Benchmark
    @Threads(2)
    public IngestionService.Result ingestNewBook_2threads() throws IngestionException, IOException {
        return service.ingest(nextId.getAndIncrement());
    }

    @Benchmark
    @Threads(4)
    public IngestionService.Result ingestNewBook_4threads() throws IngestionException, IOException {
        return service.ingest(nextId.getAndIncrement());
    }

    @Benchmark
    public IngestionService.Result ingestAlreadyAvailable() throws IngestionException, IOException {
        return service.ingest(AVAILABLE_BOOK);
    }

    @Benchmark
    public boolean statusLookup() throws IOException {
        return service.isAvailable(AVAILABLE_BOOK);
    }

    /** sample_data/ from the repository root, or ../sample_data from the module folder. */
    static Path sampleDataFolder() {
        for (Path candidate : List.of(Path.of("sample_data"), Path.of("..", "sample_data"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("sample_data folder not found; run the benchmark from the repository root");
    }
}
