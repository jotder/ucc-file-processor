package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;

import static com.gamma.acquire.SourceConnector.Capability.RANDOM_ACCESS;
import static com.gamma.acquire.SourceConnector.Capability.STREAM;
import static org.junit.jupiter.api.Assertions.*;

/** The I/O-minimisation policy: write bytes at most once — stream when nothing is kept, else fetch
 *  straight to the final home, never temp-then-move. */
class RetrievalPlannerTest {

    private static final Path BACKUP = Path.of("backup/2026/06/14/f.dat");
    private static final Path TEMP = Path.of("temp");

    @Test
    void backupConfiguredFetchesStraightToBackup() {
        RetrievalPlan p = RetrievalPlanner.plan(EnumSet.of(STREAM), true, BACKUP, TEMP, false);
        assertEquals(RetrievalPlan.Mode.FETCH_TO_BACKUP, p.mode());
        assertEquals(BACKUP, p.destination());
        assertTrue(p.materializes());
    }

    @Test
    void noBackupAndStreamableReadsStraightFromSource() {
        RetrievalPlan p = RetrievalPlanner.plan(EnumSet.of(STREAM), false, null, TEMP, false);
        assertEquals(RetrievalPlan.Mode.STREAM, p.mode());
        assertNull(p.destination());
        assertFalse(p.materializes());
    }

    @Test
    void backupWinsEvenWhenStreamable() {
        RetrievalPlan p = RetrievalPlanner.plan(EnumSet.of(STREAM, RANDOM_ACCESS), true, BACKUP, TEMP, false);
        assertEquals(RetrievalPlan.Mode.FETCH_TO_BACKUP, p.mode());
    }

    @Test
    void cannotStreamFallsBackToTempStaging() {
        RetrievalPlan p = RetrievalPlanner.plan(
                EnumSet.noneOf(SourceConnector.Capability.class), false, null, TEMP, false);
        assertEquals(RetrievalPlan.Mode.STAGE_TEMP, p.mode());
        assertEquals(TEMP, p.destination());
    }

    @Test
    void needsLocalCopyForcesStagingWhenNoBackup() {
        RetrievalPlan p = RetrievalPlanner.plan(EnumSet.of(STREAM), false, null, TEMP, true);
        assertEquals(RetrievalPlan.Mode.STAGE_TEMP, p.mode());
    }
}
