
package com.zcj.indexing;

import com.zcj.common.Database;
import com.zcj.common.Datalake;
import com.zcj.common.GutenbergBook;
import com.zcj.common.Tokenizer;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the main indexing operations.
 *
 * Dataset preparation and database creation are excluded
 * from the measured operations.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(2)

@State(Scope.Thread)
public class BookIndexerBenchmark {

    private static final List<Integer> BOOK_IDS =
            List.of(11, 84, 98, 1342, 1661);

    private static final int BENCHMARK_BOOK_ID = 1342;

    private Path temporaryDirectory;
    private Datalake datalake;
    private Connection connection;

    private String benchmarkHeader;
    private String benchmarkBody;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Path sampleDirectory = Path.of("sample_data").toAbsolutePath();

        if (!Files.isDirectory(sampleDirectory)) {
            throw new IllegalStateException(
                    "sample_data directory not found: " + sampleDirectory);
        }

        temporaryDirectory = Files.createTempDirectory("indexing-jmh-");
        datalake = new Datalake(temporaryDirectory.resolve("datalake"));

        prepareDataset(sampleDirectory);

        benchmarkHeader = Files.readString(
                datalake.headerPath(BENCHMARK_BOOK_ID),
                StandardCharsets.UTF_8);

        benchmarkBody = Files.readString(
                datalake.bodyPath(BENCHMARK_BOOK_ID),
                StandardCharsets.UTF_8);

        Path databasePath = temporaryDirectory.resolve("benchmark.db");
        connection = Database.open(databasePath.toString());
        Database.createSchema(connection);
    }

    private void prepareDataset(Path sampleDirectory) throws Exception {
        for (int bookId : BOOK_IDS) {
            Path source = sampleDirectory.resolve("pg" + bookId + ".txt");

            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException(
                        "Sample book not found: " + source);
            }

            String rawText = Files.readString(
                    source, StandardCharsets.UTF_8);

            GutenbergBook.Parts parts = GutenbergBook.split(rawText);

            Files.createDirectories(datalake.rangeDir(bookId));

            Files.writeString(
                    datalake.headerPath(bookId),
                    parts.header(),
                    StandardCharsets.UTF_8);

            Files.writeString(
                    datalake.bodyPath(bookId),
                    parts.body(),
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * Measures tokenization independently.
     */
    @Benchmark
    public List<String> tokenizeBookBody() {
        return Tokenizer.tokenize(benchmarkBody);
    }

    /**
     * Measures metadata extraction independently.
     */
    @Benchmark
    public GutenbergBook.Metadata extractBookMetadata() {
        return GutenbergBook.parseHeader(benchmarkHeader);
    }

    /**
     * Measures the transactional update of one book in SQLite.
     * Repeated invocations reindex the same book, exercising idempotency.
     */
    @Benchmark
    public BookIndexer.IndexResult updateOneBookIndex() throws Exception {
        return BookIndexer.indexBook(
                connection, datalake, BENCHMARK_BOOK_ID);
    }

    /**
     * Measures complete indexing of the five sample books.
     */
    @Benchmark
    public int indexFiveSampleBooks() throws Exception {
        int totalTokens = 0;

        for (int bookId : BOOK_IDS) {
            BookIndexer.IndexResult result =
                    BookIndexer.indexBook(connection, datalake, bookId);

            totalTokens += result.totalTokens();
        }

        return totalTokens;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }

        if (temporaryDirectory != null &&
                Files.exists(temporaryDirectory)) {
            try (var paths = Files.walk(temporaryDirectory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception e) {
                                throw new RuntimeException(
                                        "Could not delete " + path, e);
                            }
                        });
            }
        }
    }
}
