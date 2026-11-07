package com.zcj.control;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Control files, claims and configuration parsing. */
class ControlStateTest {

    @Test
    void stateSurvivesARestart() throws Exception {
        Path folder = Files.createTempDirectory("control-state");
        ControlState state = new ControlState(folder);
        state.markDownloaded(11);
        state.markDownloaded(84);
        state.markIndexed(11);
        state.markFailed(5, "ingestion 404\tnot_found\nline");

        ControlState reloaded = new ControlState(folder);

        assertEquals(new ControlState.Snapshot(2, 1, 1, 1), reloaded.snapshot());
        assertTrue(reloaded.isIndexed(11));
        assertTrue(reloaded.isFailed(5));
        assertEquals(List.of("5\tingestion 404 not_found line"),
                Files.readAllLines(folder.resolve(ControlState.FAILED_FILE)));
    }

    @Test
    void markingTwiceWritesOneLine() throws Exception {
        Path folder = Files.createTempDirectory("control-state");
        ControlState state = new ControlState(folder);
        state.markDownloaded(98);
        state.markDownloaded(98);
        state.markIndexed(98);
        state.markIndexed(98);

        assertEquals(List.of("98"), Files.readAllLines(folder.resolve(ControlState.DOWNLOADED_FILE)));
        assertEquals(List.of("98"), Files.readAllLines(folder.resolve(ControlState.INDEXED_FILE)));
    }

    @Test
    void claimedBooksAreNotGivenToAnotherWorker() throws Exception {
        ControlState state = new ControlState(Files.createTempDirectory("control-state"));
        state.markDownloaded(1342);

        assertEquals(1342, state.claimPending());
        assertNull(state.claimPending());
        assertFalse(state.claimNew(1342));   // downloaded already
        assertTrue(state.claimNew(84));
        assertFalse(state.claimNew(84));     // claimed by someone else

        state.release(1342);
        assertEquals(1342, state.claimPending());
    }

    @Test
    void lineCutByACrashIsIgnoredAndNotGluedToTheNextId() throws Exception {
        Path folder = Files.createTempDirectory("control-state");
        Files.writeString(folder.resolve(ControlState.DOWNLOADED_FILE), "11\nabc");

        ControlState state = new ControlState(folder);
        state.markDownloaded(84);

        assertEquals(List.of("11", "abc", "84"), Files.readAllLines(folder.resolve(ControlState.DOWNLOADED_FILE)));
        assertEquals(2, new ControlState(folder).snapshot().downloaded());
    }

    @Test
    void bookListFromConfiguration() {
        assertEquals(List.of(), ControlSettings.parseBookIds(""));
        assertEquals(ControlSettings.SAMPLE_BOOKS, ControlSettings.parseBookIds("sample"));
        assertEquals(List.of(1342, 11, 84), ControlSettings.parseBookIds("1342, 11,84 1342"));
        assertThrows(IllegalArgumentException.class, () -> ControlSettings.parseBookIds("12,abc"));
        assertThrows(IllegalArgumentException.class, () -> ControlSettings.parseBookIds("0"));
    }
}
