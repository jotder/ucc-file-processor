package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.PipelineConfig;

/**
 * The unified plugin-ingester engine. Every plugin ingests via the single {@link StreamingFileIngester}
 * SPI (record-by-record {@code emit}), and this strategy picks one of two execution modes per batch by
 * size — so the same ingester handles both pain points:
 *
 * <ul>
 *   <li><b>Generation mode</b> ({@link GenerationModeIngester}) — when the batch's largest member is
 *       {@code >= processing.streaming.large_file_bytes}. Each member is streamed with bounded heap
 *       and scratch; no cross-member union is performed.</li>
 *   <li><b>Union mode</b> ({@link UnionModeIngester}) — otherwise (the common many-small-files case).
 *       Per-member raw tables are unioned once after all members are ingested, then transformed →
 *       written → lineage-counted once for the whole batch.</li>
 * </ul>
 *
 * <p>Quarantine/empty semantics are identical in both modes: an ingester {@code IOException}/decode
 * error quarantines the file as {@code QUARANTINED_UNREADABLE}; zero emitted rows quarantines as
 * {@code QUARANTINED_MISMATCH}. A framework-side flush failure ({@link SinkFlushException}) is not a
 * file fault, so it fails the batch instead of quarantining the input.
 */
final class StreamingPluginBatchStrategy implements BatchIngestStrategy {

    /** Default per-generation row budget when {@code processing.streaming.flush_records} is unset. */
    static final long DEFAULT_FLUSH_ROWS = 5_000_000L;

    /** {@code > 0} forces generation mode with this row budget (test seam); {@code <= 0} = config-driven. */
    private final long forcedFlushRows;

    /** Production: read flush budget from config and pick mode by member size. */
    StreamingPluginBatchStrategy() { this.forcedFlushRows = -1L; }

    /** Test seam: force generation mode with a small {@code flushRows} so a tiny input flushes repeatedly. */
    StreamingPluginBatchStrategy(long flushRows) { this.forcedFlushRows = flushRows; }

    @Override
    public IngestOutcome ingest(Batch batch, PipelineConfig cfg) {
        long threshold = cfg.processing().largeFileBytes();
        long maxMemberBytes = batch.members().stream().mapToLong(Batch.Member::bytes).max().orElse(0L);
        boolean generationMode = forcedFlushRows > 0 || (threshold > 0 && maxMemberBytes >= threshold);

        if (generationMode) {
            long flush = forcedFlushRows > 0 ? forcedFlushRows
                    : (cfg.processing().flushRecords() > 0 ? cfg.processing().flushRecords() : DEFAULT_FLUSH_ROWS);
            return GenerationModeIngester.run(batch, cfg, flush);
        }
        return UnionModeIngester.run(batch, cfg);
    }
}
