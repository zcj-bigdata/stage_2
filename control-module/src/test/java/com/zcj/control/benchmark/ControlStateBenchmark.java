package com.zcj.control.benchmark;

import com.zcj.control.ControlState;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * JMH microbenchmarks of the bookkeeping done by the Control Module for every book,
 * i.e. the part of the coordination cost that does not depend on the services:
 *
 * <ul>
 *   <li>{@code recordBook_Nthreads}: markDownloaded + markIndexed of a new book (two lines
 *       appended and forced to disk) with 1, 2 and 4 worker threads.</li>
 *   <li>{@code claimAndRelease}: a worker looking for a pending book, with {@code books}
 *       already processed (the search walks the downloaded books).</li>
 *   <li>{@code snapshot}: GET /control/status with {@code books} already processed.</li>
 * </ul>
 *
 * Run it from the repository root:
 * <pre>
 *   ./mvnw -q -pl control-module -am test-compile exec:exec \
 *       -Dbenchmark.args="ControlStateBenchmark -rf csv -rff control-module/jmh-results.csv"
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgs = {"-Xms1G", "-Xmx1G"})
@State(Scope.Benchmark)
public class ControlStateBenchmark {

    /** Books already downloaded and indexed when the iteration starts. */
    @Param({"1000", "10000", "70000"})
    public int books;

    private Path folder;
    private ControlState state;
    private final AtomicInteger nextId = new AtomicInteger();

    @Setup(Level.Iteration)
    public void prepare() throws IOException {
        folder = Files.createTempDirectory("control-jmh");
        // Write the control files directly (faster than one fsync per book) and load them
        StringBuilder ids = new StringBuilder();
        for (int id = 1; id <= books; id++) {
            ids.append(id).append('\n');
        }
        Files.writeString(folder.resolve(ControlState.DOWNLOADED_FILE), ids + String.valueOf(books + 1) + "\n");
        Files.writeString(folder.resolve(ControlState.INDEXED_FILE), ids);
        state = new ControlState(folder);   // book books+1 is pending
        nextId.set(books + 2);
    }

    @TearDown(Level.Iteration)
    public void delete() throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Benchmark
    @Threads(1)
    public void recordBook_1thread() throws IOException {
        record();
    }

    @Benchmark
    @Threads(2)
    public void recordBook_2threads() throws IOException {
        record();
    }

    @Benchmark
    @Threads(4)
    public void recordBook_4threads() throws IOException {
        record();
    }

    private void record() throws IOException {
        int id = nextId.getAndIncrement();
        state.markDownloaded(id);
        state.markIndexed(id);
    }

    @Benchmark
    public Integer claimAndRelease() {
        Integer id = state.claimPending();
        if (id != null) {
            state.release(id);
        }
        return id;
    }

    @Benchmark
    public ControlState.Snapshot snapshot() {
        return state.snapshot();
    }
}
