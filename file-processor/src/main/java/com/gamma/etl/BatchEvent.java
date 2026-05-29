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
 * @param pipeline    pipeline name
 * @param batchId     committed batch id
 * @param status      batch status (always {@code SUCCESS} for emitted events)
 * @param partitions  distinct output partition paths this batch wrote (from lineage),
 *                    e.g. {@code event_type=CALL/year=2020/month=04/day=03} — exactly
 *                    the set a Stage-2 enrichment recompute should be scoped to
 * @param outputRows  total rows written by the batch
 */
public record BatchEvent(String pipeline, String batchId, String status,
                         List<String> partitions, long outputRows) {}
