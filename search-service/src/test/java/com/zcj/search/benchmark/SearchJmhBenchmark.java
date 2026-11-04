package com.zcj.search.benchmark;

import com.zcj.search.SampleDataSeeder;
import com.zcj.search.SearchQuery;
import com.zcj.search.SearchRepository;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH microbenchmarks of the Search Service internals, without HTTP:
 * query parsing and the datamart query for different kinds of searches.
 *
 * <p>The datamart is built once per trial with {@code books} books (the sample books
 * repeated with synthetic ids) and is not part of the measurement. The repository is
 * shared by every benchmark thread, as in the service: each thread keeps its own SQLite
 * connection, so running with {@code -t 1}, {@code -t 4} or {@code -t 8} measures how
 * concurrent searches scale inside one process.</p>
 *
 * <p>Run from the repository root:</p>
 * <pre>
 *   ./mvnw -q -pl search-service -am test-compile exec:exec -Dbenchmark.main=org.openjdk.jmh.Main \
 *       -Dbenchmark.args="SearchJmhBenchmark -t 1 -rf csv -rff benchmarks/results/search_jmh_t1.csv"
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = {"-Xms1g", "-Xmx1g"})
@State(Scope.Benchmark)
public class SearchJmhBenchmark {

    /** Size of the datamart. */
    @Param({"200"})
    public int books;

    private Path directory;
    private SearchRepository repository;

    private SearchQuery rareWord;
    private SearchQuery frequentWord;
    private SearchQuery twoWords;
    private SearchQuery filtered;
    private SearchQuery sixWords;
    private SearchQuery noMatches;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        Path sampleData = Files.isDirectory(Path.of("sample_data")) ? Path.of("sample_data") : Path.of("..", "sample_data");
        directory = Files.createTempDirectory("search-jmh");
        String db = directory.resolve("search.db").toString();
        SampleDataSeeder.seedSynthetic(sampleData, db, books);
        repository = new SearchRepository(db, true);

        rareWord = query("q", "darcy");
        frequentWord = query("q", "the");
        twoWords = query("q", "monster creature");
        filtered = query("q", "the", "author", "austen", "language", "en", "year", "1998");
        sixWords = query("q", "it was the best of times");
        noMatches = query("q", "zzzzqx");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static SearchQuery query(String... keyValues) {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return SearchQuery.parse(params::get);
    }

    // ------------------------------------------------------------- query parsing

    @Benchmark
    public SearchQuery parseQuery() {
        return query("q", "It was the best of times", "author", "Dickens", "language", "EN",
                "year", "1994", "offset", "20", "limit", "20");
    }

    // ------------------------------------------------------------- datamart queries

    /** Word that appears in one book out of five. */
    @Benchmark
    public SearchRepository.Page searchRareWord() throws Exception {
        return repository.search(rareWord);
    }

    /** Word that appears thousands of times in every book. */
    @Benchmark
    public SearchRepository.Page searchFrequentWord() throws Exception {
        return repository.search(frequentWord);
    }

    /** AND of two words. */
    @Benchmark
    public SearchRepository.Page searchTwoWords() throws Exception {
        return repository.search(twoWords);
    }

    /** Frequent word restricted by author, language and year. */
    @Benchmark
    public SearchRepository.Page searchWithFilters() throws Exception {
        return repository.search(filtered);
    }

    /** AND of six words, most of them very frequent. */
    @Benchmark
    public SearchRepository.Page searchSixWords() throws Exception {
        return repository.search(sixWords);
    }

    /** Word that is not in the index: fixed cost of a search. */
    @Benchmark
    public SearchRepository.Page searchNoMatches() throws Exception {
        return repository.search(noMatches);
    }
}
