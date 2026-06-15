package com.gamma.acquire;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The fingerprint repository (Data Acquisition roadmap Phase C): a durable record of which files a source has
 * already processed, keyed by {@code (sourceId, relativePath)}, so duplicates can be detected by <em>content</em>
 * (size / mtime / checksum) rather than only by path — closing the gap where a re-upload at the same path is
 * silently skipped (data loss) or always reprocessed.
 *
 * <p>SPI twin of the OI stores ({@link com.gamma.ops.note.NoteStore} et al.): {@link InMemoryAcquisitionLedger}
 * is the lean default; {@link DbAcquisitionLedger} is durable JDBC over the bundled DuckDB (its <b>own</b> DB
 * file, single-writer) or a Postgres URL. Unlike the append-only note/link stores this one <b>upserts</b> — a
 * file's latest fingerprint replaces the prior one for its key. The {@code PATH}-mode default keeps using
 * {@code MarkerManager} sentinels, so a pipeline with no {@code source.duplicate} block is unaffected.
 *
 * <p>Implementations must be thread-safe.
 */
public interface AcquisitionLedger extends AutoCloseable {

    /** The recorded fingerprint for {@code (sourceId, relativePath)}, or empty if this file is new to the source. */
    Optional<LedgerEntry> find(String sourceId, String relativePath);

    /** Record (insert or replace) a file's fingerprint for its {@code (sourceId, relativePath)} key. */
    void record(LedgerEntry entry);

    /**
     * The source's <b>high-watermark</b> for incremental discovery (Data Acquisition roadmap Phase C4): the
     * greatest {@link LedgerEntry#lastModified()} recorded for {@code sourceId}, or empty if the source has no
     * recorded files yet. The engine ({@code source.incremental.watermark: last_modified}) skips any
     * freshly-discovered file modified strictly before this, so a re-scan re-examines only the recent frontier
     * instead of the whole history.
     *
     * <p>Derived from what the ledger already holds — no separate watermark column — so it is only meaningful in
     * a content-based duplicate mode (the path-only default never records here). The default implementation
     * returns empty, which safely disables the optimisation for a ledger that does not track it.
     */
    default OptionalLong highWatermark(String sourceId) {
        return OptionalLong.empty();
    }

    /** Release resources (e.g. a DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
