package com.gamma.etl;

import java.util.List;

/**
 * Fired when a batch is committed — the trigger signal for downstream stages
 * (e.g. Stage-2 enrichment recomputing the partitions this batch wrote).
 *
 * <p>Emitted by {@link BatchAuditWriter} on a {@code SUCCESS} flush (after the audit
 * rows and commit-log line are written, so the event implies durability). The
 * {@code service} layer owns the pub/sub bus; this record lives in {@code etl} so the
 * low layer can emit without depending on the higher one.
 *
 * <p>Emitted for every <em>terminal</em> batch (both {@code SUCCESS} and {@code FAILED})
 * so observability sees error rates and latency; consumers that act only on success
 * (Stage-2 enrichment) filter on {@link #status()}.
 *
 * <p>Since v3.7.0 the event also carries <em>error detail</em> ({@link #error()},
 * {@link #offendingFile()}, {@link #errorRows()}) so the optional assist agent's failure-diagnosis
 * reactor (M7) has something to reason about on a {@code FAILED} batch. These are operational
 * metadata (an error message / a filename / a count), never row content. The detail is populated at
 * the emission site ({@link BatchAuditWriter#flush}); the 7-arg {@linkplain
 * #BatchEvent(String, String, String, List, long, long, int) back-compat constructor} (used by the
 * Stage-2 enrichment emitters, which only ever announce {@code SUCCESS}) defaults them to
 * {@code null}/{@code null}/{@code 0}.
 *
 * @param pipeline      pipeline name
 * @param batchId       committed batch id
 * @param status        batch status ({@code SUCCESS} or {@code FAILED})
 * @param partitions    distinct output partition paths this batch wrote (from lineage),
 *                      e.g. {@code event_type=CALL/year=2020/month=04/day=03} — exactly
 *                      the set a Stage-2 enrichment recompute should be scoped to
 *                      (empty for a failed batch)
 * @param outputRows    total rows written by the batch
 * @param durationMs    batch wall-clock duration in milliseconds
 * @param rejectedCount rejected (quarantined) member files in the batch
 * @param error         batch-level error message ({@code null}/blank when none), e.g. on a FAILED batch
 * @param offendingFile the first member file that errored/was rejected ({@code null} when none)
 * @param errorRows     total rows that failed to parse across the batch's member files
 */
public record BatchEvent(String pipeline, String batchId, String status,
                         List<String> partitions, long outputRows,
                         long durationMs, int rejectedCount,
                         String error, String offendingFile, long errorRows) {

    /**
     * Back-compat constructor (pre-v3.7.0 shape) — defaults the error detail to
     * {@code null}/{@code null}/{@code 0}. Used by the Stage-2 enrichment emitters
     * ({@code EnrichmentService}/{@code EnrichJob}), which announce only {@code SUCCESS} commits.
     */
    public BatchEvent(String pipeline, String batchId, String status,
                      List<String> partitions, long outputRows,
                      long durationMs, int rejectedCount) {
        this(pipeline, batchId, status, partitions, outputRows, durationMs, rejectedCount,
                null, null, 0L);
    }
}
