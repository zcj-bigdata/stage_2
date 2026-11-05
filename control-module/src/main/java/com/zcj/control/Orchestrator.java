package com.zcj.control;

import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * Classical orchestration of the pipeline (the same decision as the Stage 1 control
 * layer, now through the internal HTTP APIs):
 *
 * <ol>
 *   <li>If a book is downloaded but not indexed yet, index it.</li>
 *   <li>Otherwise choose a new book (never downloaded, never failed), call
 *       {@code POST /ingest/{id}} and, once it answers 200, call
 *       {@code POST /index/update/{id}}.</li>
 *   <li>Record every confirmed transition in the control files.</li>
 * </ol>
 *
 * A book becomes eligible for indexing only after the Ingestion Service has answered 200,
 * which by contract means both files are completely written. It becomes searchable when
 * the Indexing Service answers 200, which means its transaction has been committed.
 *
 * <h2>Errors</h2>
 * <ul>
 *   <li>Permanent (400, 404, 422): the book is written to failed_books.txt and never retried.</li>
 *   <li>Transient (timeout, connection refused, 409 rebuild_in_progress, 5xx): the call is
 *       retried with exponential backoff, up to {@code CONTROL_MAX_ATTEMPTS} times. If it
 *       still fails, the book is left for a later step.</li>
 *   <li>After an ingestion timeout the Control Module asks {@code GET /ingest/status/{id}}
 *       before retrying: the download may have finished after the caller gave up.</li>
 * </ul>
 * Retrying is always safe because both services are idempotent.
 */
public class Orchestrator {

    /** Result of one step, used by the loop to decide whether to wait. */
    public enum Outcome { INDEXED, FAILED, RETRY_LATER, IDLE }

    private enum CallResult { DONE, FAILED, RETRY_LATER }

    private static final int RANDOM_TRIES = 10;

    private final ControlSettings settings;
    private final ControlState state;
    private final ServiceClient client;
    private final ControlMetrics metrics;
    private final Random random = new Random();

    public Orchestrator(ControlSettings settings, ControlState state, ServiceClient client, ControlMetrics metrics) {
        this.settings = settings;
        this.state = state;
        this.client = client;
        this.metrics = metrics;
    }

    /** Processes at most one book. Safe to call from several threads at the same time. */
    public Outcome step() throws InterruptedException {
        Integer pending = state.claimPending();
        if (pending != null) {
            return process(pending, false);
        }
        Integer candidate = nextCandidate();
        if (candidate == null) {
            return Outcome.IDLE;
        }
        return process(candidate, true);
    }

    /** Processes one specific book (used by tests and by a manual run). */
    public Outcome processBook(int bookId) throws InterruptedException {
        if (!state.claimUnfinished(bookId)) {
            return Outcome.IDLE;
        }
        return process(bookId, !state.isDownloaded(bookId));
    }

    private Outcome process(int bookId, boolean needsIngestion) throws InterruptedException {
        long start = System.nanoTime();
        try {
            if (needsIngestion) {
                CallResult ingested = ingest(bookId);
                if (ingested != CallResult.DONE) {
                    return ingested == CallResult.FAILED ? Outcome.FAILED : Outcome.RETRY_LATER;
                }
            }
            CallResult indexed = index(bookId);
            if (indexed == CallResult.DONE) {
                long nanos = System.nanoTime() - start;
                metrics.bookIndexed(nanos);
                log("Book %d indexed and searchable (%d ms)", bookId, nanos / 1_000_000);
                return Outcome.INDEXED;
            }
            return indexed == CallResult.FAILED ? Outcome.FAILED : Outcome.RETRY_LATER;
        } catch (IOException e) {
            // The control files could not be written: nothing was recorded, so the step
            // will be repeated (both services are idempotent).
            log("Could not record the state of book %d: %s", bookId, e.getMessage());
            return Outcome.RETRY_LATER;
        } finally {
            state.release(bookId);
        }
    }

    // ------------------------------------------------------------------ ingestion

    private CallResult ingest(int bookId) throws IOException, InterruptedException {
        String url = settings.ingestionUrl() + "/ingest/" + bookId;
        for (int attempt = 1; ; attempt++) {
            long t0 = System.nanoTime();
            try {
                ServiceClient.Reply reply = client.post(url, settings.ingestTimeout());
                metrics.ingestCall(System.nanoTime() - t0);
                if (reply.ok()) {
                    state.markDownloaded(bookId);
                    log("Book %d %s", bookId, reply.field("status"));
                    return CallResult.DONE;
                }
                if (reply.status() == 400 || reply.status() == 404 || reply.status() == 422) {
                    fail(bookId, "ingestion " + reply.describe());
                    return CallResult.FAILED;
                }
                log("Ingestion of book %d failed (%s), attempt %d of %d",
                        bookId, reply.describe(), attempt, settings.maxAttempts());
            } catch (IOException e) {
                metrics.ingestCall(System.nanoTime() - t0);
                log("Ingestion of book %d did not answer (%s), attempt %d of %d",
                        bookId, describe(e), attempt, settings.maxAttempts());
                // The download may have finished after we stopped waiting
                if (ingestionCompleted(bookId)) {
                    state.markDownloaded(bookId);
                    log("Book %d was ingested after all (confirmed by /ingest/status)", bookId);
                    return CallResult.DONE;
                }
            }
            if (attempt >= settings.maxAttempts()) {
                log("Giving up on the ingestion of book %d for now", bookId);
                return CallResult.RETRY_LATER;
            }
            backoff(attempt);
        }
    }

    private boolean ingestionCompleted(int bookId) throws InterruptedException {
        try {
            ServiceClient.Reply reply = client.get(
                    settings.ingestionUrl() + "/ingest/status/" + bookId, settings.statusTimeout());
            return reply.ok() && "available".equals(reply.field("status"));
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ indexing

    private CallResult index(int bookId) throws IOException, InterruptedException {
        String url = settings.indexingUrl() + "/index/update/" + bookId;
        for (int attempt = 1; ; attempt++) {
            long t0 = System.nanoTime();
            try {
                ServiceClient.Reply reply = client.post(url, settings.indexTimeout());
                metrics.indexCall(System.nanoTime() - t0);
                if (reply.ok()) {
                    state.markIndexed(bookId);
                    return CallResult.DONE;
                }
                if (reply.status() == 400 || reply.status() == 404) {
                    // 404: the files are not in the datalake although ingestion confirmed them
                    // (the datalake was deleted or DATALAKE_PATH differs between services)
                    fail(bookId, "indexing " + reply.describe());
                    return CallResult.FAILED;
                }
                if (reply.status() == 409) {
                    log("Indexing is rebuilding the index, book %d waits (attempt %d of %d)",
                            bookId, attempt, settings.maxAttempts());
                } else {
                    log("Indexing of book %d failed (%s), attempt %d of %d",
                            bookId, reply.describe(), attempt, settings.maxAttempts());
                }
            } catch (IOException e) {
                metrics.indexCall(System.nanoTime() - t0);
                log("Indexing of book %d did not answer (%s), attempt %d of %d",
                        bookId, describe(e), attempt, settings.maxAttempts());
            }
            if (attempt >= settings.maxAttempts()) {
                log("Book %d stays pending; it will be indexed in a later step", bookId);
                return CallResult.RETRY_LATER;
            }
            backoff(attempt);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Next book to download: from CONTROL_BOOKS in order, or a random id. */
    private Integer nextCandidate() {
        List<Integer> ids = settings.bookIds();
        if (!ids.isEmpty()) {
            for (int bookId : ids) {
                if (state.claimNew(bookId)) {
                    return bookId;
                }
            }
            return null;
        }
        for (int i = 0; i < RANDOM_TRIES; i++) {
            int bookId = 1 + random.nextInt(settings.totalBooks());
            if (state.claimNew(bookId)) {
                return bookId;
            }
        }
        return null;
    }

    private void fail(int bookId, String reason) throws IOException {
        state.markFailed(bookId, reason);
        metrics.bookFailed();
        log("Book %d failed permanently: %s", bookId, reason);
    }

    /** Waits backoff * 2^(attempt-1): 1 s, 2 s, 4 s... with the defaults. */
    private void backoff(int attempt) throws InterruptedException {
        long millis = settings.backoff().toMillis() << Math.min(attempt - 1, 10);
        metrics.retry(millis * 1_000_000);
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void log(String format, Object... args) {
        System.out.printf("[CONTROL] [%s] %s%n", Thread.currentThread().getName(), String.format(format, args));
    }
}
