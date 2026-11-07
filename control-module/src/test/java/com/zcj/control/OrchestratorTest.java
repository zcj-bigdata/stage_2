package com.zcj.control;

import com.zcj.control.FakeServices.Answer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Orchestration against fake services: workflow order, permanent and transient
 * errors, timeouts, 409 during a rebuild and recovery after a restart.
 */
class OrchestratorTest {

    private static ControlSettings settings(FakeServices fake, Path controlPath, List<Integer> books) {
        return new ControlSettings(fake.url(), fake.url(), controlPath.toString(), books, 70000, 0, 1,
                Duration.ZERO, 3, Duration.ofMillis(10),
                Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(300));
    }

    private static Orchestrator orchestrator(ControlSettings settings, ControlState state) {
        return new Orchestrator(settings, state, new ServiceClient(), new ControlMetrics());
    }

    @Test
    void ingestsThenIndexesAndRecordsBothSteps() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);
            Orchestrator orchestrator = orchestrator(settings(fake, control, List.of(1342)), state);

            assertEquals(Orchestrator.Outcome.INDEXED, orchestrator.step());

            assertEquals(List.of("POST /ingest/1342", "POST /index/update/1342"), fake.calls);
            assertEquals(new ControlState.Snapshot(1, 1, 0, 0), state.snapshot());
            assertEquals(List.of("1342"), Files.readAllLines(control.resolve(ControlState.DOWNLOADED_FILE)));
            assertEquals(List.of("1342"), Files.readAllLines(control.resolve(ControlState.INDEXED_FILE)));
            // Nothing else to do in the list
            assertEquals(Orchestrator.Outcome.IDLE, orchestrator.step());
        }
    }

    @Test
    void bookThatDoesNotExistFailsWithoutCallingIndexing() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            fake.scriptIngest(Answer.status(404));
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);

            assertEquals(Orchestrator.Outcome.FAILED, orchestrator(settings(fake, control, List.of(5)), state).step());

            assertEquals(List.of("POST /ingest/5"), fake.calls);
            assertTrue(state.isFailed(5));
            String line = Files.readAllLines(control.resolve(ControlState.FAILED_FILE)).get(0);
            assertTrue(line.startsWith("5\tingestion 404 not_found"), line);
        }
    }

    @Test
    void transientIngestionErrorsAreRetried() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            fake.scriptIngest(Answer.status(502), Answer.status(502));
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);

            assertEquals(Orchestrator.Outcome.INDEXED, orchestrator(settings(fake, control, List.of(84)), state).step());

            assertEquals(3, fake.count("POST /ingest/84"));
            assertTrue(state.isIndexed(84));
        }
    }

    @Test
    void afterAnIngestionTimeoutTheStatusIsCheckedBeforeRetrying() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            // The book is stored but the answer arrives after the 300 ms timeout
            fake.scriptIngest(Answer.slow(1000));
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);

            assertEquals(Orchestrator.Outcome.INDEXED, orchestrator(settings(fake, control, List.of(98)), state).step());

            assertEquals(1, fake.count("POST /ingest/98"));
            assertEquals(1, fake.count("GET /ingest/status/98"));
            assertTrue(state.isIndexed(98));
        }
    }

    @Test
    void rebuildInProgressMakesTheBookWaitAndRetry() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            fake.scriptIndex(Answer.status(409));
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);

            assertEquals(Orchestrator.Outcome.INDEXED, orchestrator(settings(fake, control, List.of(11)), state).step());

            assertEquals(1, fake.count("POST /ingest/11"));
            assertEquals(2, fake.count("POST /index/update/11"));
        }
    }

    @Test
    void indexingDownLeavesTheBookPendingAndTheNextStepIndexesItWithoutDownloading() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            fake.scriptIndex(Answer.status(500), Answer.status(500), Answer.status(500));
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);
            Orchestrator orchestrator = orchestrator(settings(fake, control, List.of(1661, 84)), state);

            assertEquals(Orchestrator.Outcome.RETRY_LATER, orchestrator.step());
            assertEquals(new ControlState.Snapshot(1, 0, 0, 1), state.snapshot());
            assertFalse(state.isFailed(1661));

            // Pending books go first, before downloading book 84
            assertEquals(Orchestrator.Outcome.INDEXED, orchestrator.step());
            assertEquals(1, fake.count("POST /ingest/1661"));
            assertEquals(4, fake.count("POST /index/update/1661"));
            assertEquals(0, fake.count("POST /ingest/84"));
        }
    }

    @Test
    void restartResumesPendingBooksFromTheControlFiles() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            Path control = Files.createTempDirectory("control-test");
            // State left by a previous run that stopped after the ingestion of 1342
            Files.writeString(control.resolve(ControlState.DOWNLOADED_FILE), "11\n1342\n");
            Files.writeString(control.resolve(ControlState.INDEXED_FILE), "11\n");
            ControlState state = new ControlState(control);
            assertEquals(new ControlState.Snapshot(2, 1, 0, 1), state.snapshot());

            Orchestrator orchestrator = orchestrator(settings(fake, control, List.of(11, 1342)), state);
            assertEquals(Orchestrator.Outcome.INDEXED, orchestrator.step());
            assertEquals(Orchestrator.Outcome.IDLE, orchestrator.step());

            assertEquals(List.of("POST /index/update/1342"), fake.calls);
            assertEquals(new ControlState.Snapshot(2, 2, 0, 0), state.snapshot());
        }
    }

    @Test
    void severalWorkersNeverProcessTheSameBook() throws Exception {
        try (FakeServices fake = new FakeServices()) {
            Path control = Files.createTempDirectory("control-test");
            ControlState state = new ControlState(control);
            List<Integer> books = List.of(11, 84, 98, 1342, 1661, 5, 6, 7, 8, 9);
            Orchestrator orchestrator = orchestrator(settings(fake, control, books), state);

            Thread[] workers = new Thread[4];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(() -> {
                    try {
                        while (orchestrator.step() != Orchestrator.Outcome.IDLE) {
                            // keep going until the list is exhausted
                        }
                    } catch (InterruptedException ignored) {
                        // test ends
                    }
                });
                workers[i].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }

            assertEquals(new ControlState.Snapshot(10, 10, 0, 0), state.snapshot());
            for (int id : books) {
                assertEquals(1, fake.count("POST /ingest/" + id), "ingest calls of " + id);
                assertEquals(1, fake.count("POST /index/update/" + id), "index calls of " + id);
            }
        }
    }
}
