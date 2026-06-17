package com.gamma.flow.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T11 — the {@code (batch,branch)} commit-split ({@link BranchCommitLog} + {@link BranchCommitCoordinator}):
 * partial-commit state is durable, a batch commits per-branch and finalises the source only once all
 * branches are durable, and a crash mid-commit resumes idempotently (no re-commit, no double-finalise).
 */
class BranchCommitTest {

    @TempDir Path dir;

    private String logPath() {
        return dir.resolve("branch_commit.csv").toString();
    }

    @Test
    void logRecordsBranchesAndSourceFinalisationDurably() {
        BranchCommitLog log = new BranchCommitLog(logPath());
        assertTrue(log.committedBranches("batch1").isEmpty());

        log.recordBranch("batch1", "data");
        log.recordBranch("batch1", "route:emea");
        assertEquals(Set.of("data", "route:emea"), log.committedBranches("batch1"));
        assertFalse(log.isSourceFinalized("batch1"));

        log.recordSourceFinalized("batch1");
        assertTrue(log.isSourceFinalized("batch1"));

        // reopening the file recovers the same state (the fsync'd ledger is the source of truth)
        BranchCommitLog reopened = new BranchCommitLog(logPath());
        assertEquals(Set.of("data", "route:emea"), reopened.committedBranches("batch1"));
        assertTrue(reopened.isSourceFinalized("batch1"));
    }

    @Test
    void commitsEveryBranchThenFinalisesSourceExactlyOnce() throws Exception {
        BranchCommitCoordinator coord = new BranchCommitCoordinator(new BranchCommitLog(logPath()));
        List<String> committed = new ArrayList<>();
        int[] finalised = {0};

        BranchCommitCoordinator.Result res = coord.commit("b",
                new LinkedHashSet<>(List.of("sinkA", "sinkB")),
                committed::add, () -> finalised[0]++);

        assertEquals(List.of("sinkA", "sinkB"), committed);
        assertEquals(1, finalised[0]);
        assertTrue(res.sourceFinalized());
    }

    @Test
    void resumesAfterACrashWithoutRecommitOrDoubleFinalise() {
        // attempt 1: sinkA commits, sinkB throws (crash) -> sinkA durable, no finalisation
        BranchCommitLog log1 = new BranchCommitLog(logPath());
        List<String> committed1 = new ArrayList<>();
        int[] finalised = {0};
        assertThrows(RuntimeException.class, () -> new BranchCommitCoordinator(log1).commit("b",
                new LinkedHashSet<>(List.of("sinkA", "sinkB")),
                branch -> {
                    if (branch.equals("sinkB")) throw new RuntimeException("crash before sinkB durable");
                    committed1.add(branch);
                },
                () -> finalised[0]++));
        assertEquals(List.of("sinkA"), committed1);
        assertEquals(Set.of("sinkA"), log1.committedBranches("b"));
        assertEquals(0, finalised[0]);
        assertFalse(log1.isSourceFinalized("b"));

        // attempt 2 (recovery): reopen the ledger, commit succeeds -> only sinkB committed, finalise once
        BranchCommitLog log2 = new BranchCommitLog(logPath());
        List<String> committed2 = new ArrayList<>();
        BranchCommitCoordinator.Result res = assertDoesNotThrow(() ->
                new BranchCommitCoordinator(log2).commit("b",
                        new LinkedHashSet<>(List.of("sinkA", "sinkB")),
                        committed2::add, () -> finalised[0]++));
        assertEquals(List.of("sinkB"), committed2, "sinkA must NOT be re-committed");
        assertEquals(1, finalised[0], "source finalised exactly once");
        assertTrue(res.sourceFinalized());

        // attempt 3: fully done -> nothing happens, no double-finalise
        BranchCommitCoordinator.Result res3 = assertDoesNotThrow(() ->
                new BranchCommitCoordinator(new BranchCommitLog(logPath())).commit("b",
                        Set.of("sinkA", "sinkB"), c -> fail("should not re-commit " + c),
                        () -> finalised[0]++));
        assertFalse(res3.sourceFinalized());
        assertEquals(1, finalised[0]);
    }
}
