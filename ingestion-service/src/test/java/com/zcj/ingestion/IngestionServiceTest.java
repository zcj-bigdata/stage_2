package com.zcj.ingestion;

import com.zcj.common.Datalake;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Datalake writes, idempotency, concurrent requests and recovery after a crash. */
class IngestionServiceTest {

    static final Path SAMPLE_DATA = Path.of("..", "sample_data");

    private static Datalake newDatalake() throws Exception {
        return new Datalake(Files.createTempDirectory("ingestion-test").resolve("datalake"));
    }

    @Test
    void ingestWritesHeaderAndBodyInTheRangeFolder() throws Exception {
        Datalake datalake = newDatalake();
        IngestionService service = new IngestionService(datalake, new SampleDataSource(SAMPLE_DATA));

        IngestionService.Result result = service.ingest(1342);

        assertEquals(1342, result.bookId());
        assertEquals("downloaded", result.status());
        assertTrue(result.path().endsWith("ranges/001000-001999/1342"), result.path());
        String header = Files.readString(datalake.headerPath(1342), StandardCharsets.UTF_8);
        String body = Files.readString(datalake.bodyPath(1342), StandardCharsets.UTF_8);
        assertTrue(header.contains("Title: Pride and Prejudice"));
        assertFalse(body.contains("*** START OF THE PROJECT GUTENBERG EBOOK"));
        assertFalse(body.contains("*** END OF THE PROJECT GUTENBERG EBOOK"));
        assertTrue(service.isAvailable(1342));
        assertEquals(List.of("1342"), Files.readAllLines(datalake.root().resolve(IngestionService.REGISTRY_FILE)));
    }

    @Test
    void secondIngestionDoesNotDownloadAgain() throws Exception {
        CountingSource source = new CountingSource(new SampleDataSource(SAMPLE_DATA));
        IngestionService service = new IngestionService(newDatalake(), source);

        assertEquals("downloaded", service.ingest(84).status());
        assertEquals("already_available", service.ingest(84).status());
        assertEquals(1, source.calls.get());
    }

    @Test
    void concurrentRequestsForTheSameBookDownloadItOnce() throws Exception {
        Datalake datalake = newDatalake();
        CountingSource source = new CountingSource(new SampleDataSource(SAMPLE_DATA));
        IngestionService service = new IngestionService(datalake, source);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> service.ingest(11).status());
        }
        int downloaded = 0;
        for (Future<String> f : pool.invokeAll(tasks)) {
            if ("downloaded".equals(f.get())) {
                downloaded++;
            }
        }
        pool.shutdown();

        assertEquals(1, downloaded);
        assertEquals(1, source.calls.get());
        assertEquals(List.of(11), service.list());
        assertEquals(0, countTmpFiles(datalake));
    }

    @Test
    void unknownBookIsNotFound() throws Exception {
        IngestionService service = new IngestionService(newDatalake(), new SampleDataSource(SAMPLE_DATA));

        IngestionException e = assertThrows(IngestionException.class, () -> service.ingest(99999999));
        assertEquals(IngestionException.Kind.NOT_FOUND, e.kind());
        assertFalse(service.isAvailable(99999999));
    }

    @Test
    void textWithoutMarkersIsInvalidAndNothingIsStored() throws Exception {
        Path folder = Files.createTempDirectory("bad-books");
        Files.writeString(folder.resolve("pg7.txt"), "Title: no markers here\nJust text\n");
        Datalake datalake = newDatalake();
        IngestionService service = new IngestionService(datalake, new SampleDataSource(folder));

        IngestionException e = assertThrows(IngestionException.class, () -> service.ingest(7));
        assertEquals(IngestionException.Kind.INVALID_BOOK, e.kind());
        assertFalse(Files.exists(datalake.headerPath(7)));
        assertFalse(Files.exists(datalake.bodyPath(7)));
    }

    @Test
    void restartKeepsRecordedBooksAndRecoversUnrecordedOnes() throws Exception {
        Datalake datalake = newDatalake();
        new IngestionService(datalake, new SampleDataSource(SAMPLE_DATA)).ingest(98);

        // Simulate a crash: book 1661 was renamed but not recorded, and a download of
        // book 1342 left a temporary file behind
        Files.createDirectories(datalake.rangeDir(1661));
        Files.writeString(datalake.bodyPath(1661), "body");
        Files.writeString(datalake.headerPath(1661), "Title: X");
        Path tmp = datalake.rangeDir(1342).resolve("1342.body.txt.1234.tmp");
        Files.createDirectories(tmp.getParent());
        Files.writeString(tmp, "half a book");

        CountingSource source = new CountingSource(new SampleDataSource(SAMPLE_DATA));
        IngestionService restarted = new IngestionService(datalake, source);

        assertEquals(List.of(98, 1661), restarted.list());
        assertFalse(Files.exists(tmp));
        assertFalse(restarted.isAvailable(1342));
        assertEquals("already_available", restarted.ingest(98).status());
        assertEquals(0, source.calls.get());
    }

    @Test
    void bookWithOnlyTheBodyIsNotAvailable() throws Exception {
        Datalake datalake = newDatalake();
        Files.createDirectories(datalake.rangeDir(5));
        Files.writeString(datalake.bodyPath(5), "body without header");

        IngestionService service = new IngestionService(datalake, new SampleDataSource(SAMPLE_DATA));

        assertFalse(service.isAvailable(5));
        assertEquals(List.of(), service.list());
    }

    @Test
    void bookIdMustBeAPositiveInteger() {
        assertEquals(1342, IngestionController.parseBookId("1342"));
        IllegalArgumentException notInteger =
                assertThrows(IllegalArgumentException.class, () -> IngestionController.parseBookId("abc"));
        assertEquals("book_id must be an integer", notInteger.getMessage());
        IllegalArgumentException notPositive =
                assertThrows(IllegalArgumentException.class, () -> IngestionController.parseBookId("0"));
        assertEquals("book_id must be positive", notPositive.getMessage());
    }

    static long countTmpFiles(Datalake datalake) throws Exception {
        try (Stream<Path> files = Files.walk(datalake.root())) {
            return files.filter(p -> p.toString().endsWith(".tmp")).count();
        }
    }

    /** Counts how many times the source is actually read. */
    static class CountingSource implements BookSource {
        final BookSource delegate;
        final AtomicInteger calls = new AtomicInteger();

        CountingSource(BookSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public String fetch(int bookId) throws IngestionException {
            calls.incrementAndGet();
            return delegate.fetch(bookId);
        }

        @Override
        public String describe() {
            return delegate.describe();
        }
    }
}
