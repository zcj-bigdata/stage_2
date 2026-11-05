package com.zcj.control;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Coordination cost measured by the Control Module since it started, returned by
 * {@code GET /control/metrics}. Times are measured on the caller side, so they include
 * the HTTP round trip and the time each service spends on the book.
 */
public class ControlMetrics {

    private final long startedAt = System.nanoTime();

    private long ingestCalls;
    private long ingestNanos;
    private long indexCalls;
    private long indexNanos;
    private long retries;
    private long backoffNanos;
    private long booksIndexed;
    private long booksFailed;
    private long bookNanos;

    public synchronized void ingestCall(long nanos) {
        ingestCalls++;
        ingestNanos += nanos;
    }

    public synchronized void indexCall(long nanos) {
        indexCalls++;
        indexNanos += nanos;
    }

    public synchronized void retry(long waitNanos) {
        retries++;
        backoffNanos += waitNanos;
    }

    /** End-to-end time of a book: from the first ingestion call to the indexing confirmation. */
    public synchronized void bookIndexed(long nanos) {
        booksIndexed++;
        bookNanos += nanos;
    }

    public synchronized void bookFailed() {
        booksFailed++;
    }

    public synchronized Map<String, Object> toMap() {
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1e9;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptime_s", round(elapsedSeconds));
        m.put("books_indexed", booksIndexed);
        m.put("books_failed", booksFailed);
        m.put("books_per_second", round(elapsedSeconds > 0 ? booksIndexed / elapsedSeconds : 0));
        m.put("ingest_calls", ingestCalls);
        m.put("avg_ingest_ms", averageMillis(ingestNanos, ingestCalls));
        m.put("index_calls", indexCalls);
        m.put("avg_index_ms", averageMillis(indexNanos, indexCalls));
        m.put("avg_book_ms", averageMillis(bookNanos, booksIndexed));
        m.put("retries", retries);
        m.put("backoff_wait_ms", round(backoffNanos / 1e6));
        return m;
    }

    private static double averageMillis(long nanos, long count) {
        return count == 0 ? 0 : round(nanos / 1e6 / count);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
