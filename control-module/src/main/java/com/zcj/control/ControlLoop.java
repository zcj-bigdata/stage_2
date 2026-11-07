package com.zcj.control;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the orchestration in the background with {@code CONTROL_WORKERS} threads.
 * Each worker repeats {@link Orchestrator#step()}; the claims in {@link ControlState}
 * guarantee that two workers never process the same book.
 *
 * A worker stops when:
 * <ul>
 *   <li>{@code CONTROL_MAX_BOOKS} books have been indexed in this run (0 = no limit), or</li>
 *   <li>the books of {@code CONTROL_BOOKS} are all processed (nothing left to claim).</li>
 * </ul>
 * With random ids the loop never runs out of candidates, so it only stops at the limit.
 */
public class ControlLoop {

    private static final Duration SERVICE_WAIT = Duration.ofSeconds(2);

    private final ControlSettings settings;
    private final Orchestrator orchestrator;
    private final ControlState state;
    private final ServiceClient client;

    private final AtomicInteger indexedThisRun = new AtomicInteger();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final List<Thread> threads = new ArrayList<>();

    public ControlLoop(ControlSettings settings, Orchestrator orchestrator, ControlState state, ServiceClient client) {
        this.settings = settings;
        this.orchestrator = orchestrator;
        this.state = state;
        this.client = client;
    }

    public synchronized void start() {
        Thread starter = new Thread(this::run, "control-loop");
        starter.setDaemon(true);
        threads.add(starter);
        starter.start();
    }

    public void stop() {
        stopped.set(true);
        synchronized (this) {
            threads.forEach(Thread::interrupt);
        }
    }

    public boolean isRunning() {
        return activeWorkers.get() > 0;
    }

    public int indexedThisRun() {
        return indexedThisRun.get();
    }

    private void run() {
        try {
            waitFor("Ingestion Service", settings.ingestionUrl());
            waitFor("Indexing Service", settings.indexingUrl());
        } catch (InterruptedException e) {
            return;
        }

        log("Starting %d worker(s); books: %s; limit: %s", settings.workers(),
                settings.bookIds().isEmpty() ? "random ids 1-" + settings.totalBooks() : settings.bookIds(),
                settings.maxBooks() == 0 ? "none" : settings.maxBooks() + " books");

        List<Thread> workers = new ArrayList<>();
        for (int i = 1; i <= settings.workers(); i++) {
            Thread worker = new Thread(this::work, "worker-" + i);
            worker.setDaemon(true);
            workers.add(worker);
        }
        synchronized (this) {
            threads.addAll(workers);
        }
        activeWorkers.set(workers.size());
        workers.forEach(Thread::start);
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                return;
            }
        }
        ControlState.Snapshot s = state.snapshot();
        log("Finished: %d indexed in this run; totals downloaded=%d indexed=%d failed=%d pending=%d",
                indexedThisRun.get(), s.downloaded(), s.indexed(), s.failed(), s.pending());
    }

    private void work() {
        try {
            while (!stopped.get() && !limitReached()) {
                Orchestrator.Outcome outcome = orchestrator.step();
                switch (outcome) {
                    case INDEXED -> indexedThisRun.incrementAndGet();
                    case FAILED -> {
                        // Permanent error of this book: go on with the next one
                    }
                    case RETRY_LATER -> pause(settings.interval());
                    case IDLE -> {
                        if (!settings.bookIds().isEmpty()) {
                            return;   // every book of the list is processed or claimed
                        }
                        pause(settings.interval());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    private boolean limitReached() {
        return settings.maxBooks() > 0 && indexedThisRun.get() >= settings.maxBooks();
    }

    /** Waits until GET {url}/status answers 200, so the services can be started in any order. */
    private void waitFor(String name, String url) throws InterruptedException {
        boolean warned = false;
        while (!stopped.get()) {
            try {
                if (client.get(url + "/status", Duration.ofSeconds(5)).ok()) {
                    log("%s is up at %s", name, url);
                    return;
                }
            } catch (IOException e) {
                // not started yet
            }
            if (!warned) {
                log("Waiting for the %s at %s ...", name, url);
                warned = true;
            }
            pause(SERVICE_WAIT);
        }
    }

    private static void pause(Duration duration) throws InterruptedException {
        if (!duration.isZero()) {
            Thread.sleep(duration.toMillis());
        }
    }

    private static void log(String format, Object... args) {
        System.out.printf("[CONTROL] %s%n", String.format(format, args));
    }
}
