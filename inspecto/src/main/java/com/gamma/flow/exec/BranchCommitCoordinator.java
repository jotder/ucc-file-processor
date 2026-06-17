package com.gamma.flow.exec;

import com.gamma.api.PublicApi;

import java.util.Set;

/**
 * <b>T11 — the commit-split.</b> Splits a batch commit into <b>per-branch</b> commit
 * (register + manifest, one branch at a time) and a single <b>source-finalisation</b>
 * (backup → markers LAST → ledger / watermark LAST) that runs <em>only after every branch is durable</em>
 * — generalising the legacy single-output {@code BatchProcessor.commit} to a branch-aware flow without
 * losing its crash-ordering invariant.
 *
 * <p>Driven by {@link BranchCommitLog} (partial-commit state), the coordinator is <b>idempotent and
 * crash-safe</b>:
 * <ul>
 *   <li>a branch already recorded {@code BRANCH}-committed is skipped on replay (branch outputs are
 *       written with overwrite-or-ignore, so re-running a partially-committed batch is safe);</li>
 *   <li>source-finalisation runs <b>exactly once</b>, and only once <em>all</em> expected branches are
 *       committed — a crash between the last branch and finalisation is recovered by re-running, which
 *       finalises without re-committing any branch.</li>
 * </ul>
 *
 * <p>A single-branch flow (one expected branch) reduces to "commit the one branch, then finalise the
 * source" — the same observable sequence as today, so the legacy path's behaviour is preserved.
 */
@PublicApi(since = "4.3.0")
public final class BranchCommitCoordinator {

    /** Write one branch's outputs + manifest durably (idempotent — overwrite-or-ignore). */
    @FunctionalInterface
    public interface BranchCommit {
        void commit(String branch) throws Exception;
    }

    /** Finalise the source files for the batch: backup → markers LAST → ledger / watermark LAST. */
    @FunctionalInterface
    public interface SourceFinalize {
        void finalizeSource() throws Exception;
    }

    /** What a {@link #commit} call actually did (for audit / tests). */
    public record Result(java.util.List<String> committedBranches, boolean sourceFinalized) {}

    private final BranchCommitLog log;

    public BranchCommitCoordinator(BranchCommitLog log) {
        this.log = log;
    }

    /**
     * Commit {@code batchId} across {@code expectedBranches}, then finalise the source once all are durable.
     *
     * @param batchId          the batch being committed
     * @param expectedBranches every branch this batch must commit (e.g. one {@code sink} per route);
     *                         finalisation is gated on all of them
     * @param branchCommit     writes a single branch's outputs + manifest (called once per not-yet-committed branch)
     * @param sourceFinalize   the source-finalisation step (markers LAST); called at most once, only when all done
     * @return what happened — the branches committed in this call + whether the source was finalised here
     */
    public Result commit(String batchId, Set<String> expectedBranches,
                         BranchCommit branchCommit, SourceFinalize sourceFinalize) throws Exception {
        if (expectedBranches.isEmpty())
            throw new IllegalArgumentException("batch '" + batchId + "' has no branches to commit");

        java.util.List<String> committedNow = new java.util.ArrayList<>();
        Set<String> already = log.committedBranches(batchId);
        for (String branch : expectedBranches) {
            if (already.contains(branch)) continue;     // idempotent replay — already durable
            branchCommit.commit(branch);
            log.recordBranch(batchId, branch);          // durable: this branch is committed
            committedNow.add(branch);
        }

        boolean finalizedNow = false;
        boolean allCommitted = log.committedBranches(batchId).containsAll(expectedBranches);
        if (allCommitted && !log.isSourceFinalized(batchId)) {
            sourceFinalize.finalizeSource();            // backup -> markers LAST -> ledger/watermark LAST
            log.recordSourceFinalized(batchId);
            finalizedNow = true;
        }
        return new Result(committedNow, finalizedNow);
    }
}
