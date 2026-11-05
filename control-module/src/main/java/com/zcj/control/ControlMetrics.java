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
    /** Start of the first service call and end of the last indexed book (0 = none yet). */
    private long firstCallAt;
    private long lastIndexedAt;

    public synchronized void ingestCall(long nanos) {
        workStarted(nanos);
        ingestCalls++;
        ingestNanos += nanos;
    }

    public synchronized void indexCall(long nanos) {
        workStarted(nanos);
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
        lastIndexedAt = System.nanoTime();
    }

    /** Remembers when the first call of the run started (it ended now, after {@code nanos}). */
    private void workStarted(long nanos) {
        if (firstCallAt == 0) {
            firstCallAt = System.nanoTime() - nanos;
        }
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
        // Throughput over the time spent working (first call -> last indexed book), not over
        // the uptime: the process may stay idle for a long time after the last book.
        double activeSeconds = lastIndexedAt > firstCallAt && firstCallAt != 0
                ? (lastIndexedAt - firstCallAt) / 1e9 : 0;
        m.put("active_s", round(activeSeconds));
        m.put("books_per_second", round(activeSeconds > 0 ? booksIndexed / activeSeconds : 0));
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
