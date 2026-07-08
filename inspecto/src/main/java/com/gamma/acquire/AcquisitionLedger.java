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

    /**
     * The stored <b>row-level DB watermark</b> for {@code sourceKey} (the DB-export connector's connection-profile
     * id), or empty if none has been recorded yet. Unlike {@link #highWatermark(String)} this is <em>not</em>
     * derived from file metadata — it is an opaque value (the max of a monotonic result column such as
     * {@code updated_at} / {@code id}) that {@code DbExportConnector} persists after a batch commits and reads back
     * to bind into {@code … WHERE col > :watermark} on the next cycle, so only newer rows are exported.
     *
     * <p>The value is stored as text; the connector owns its type (string / long / timestamp). The default
     * implementation returns empty, which safely disables row-watermarking for a ledger that does not track it.
     */
    default Optional<String> dbWatermark(String sourceKey) {
        return Optional.empty();
    }

    /**
     * Record (insert or replace) the row-level DB watermark for {@code sourceKey}. Called only <b>after</b> the
     * batch carrying those rows has committed (so a crash mid-ingest re-exports the slice rather than skipping it —
     * at-least-once / resumable). The default implementation is a no-op.
     */
    default void recordDbWatermark(String sourceKey, String value) {}

    /**
     * Delete fingerprints processed before {@code processedBefore} (epoch millis), optionally scoped to one
     * {@code sourceId} (PIP-7 {@code ledger_prune} maintenance task). Returns the number of rows removed.
     *
     * <p><b>Deliberate forgetting:</b> a pruned file that still exists at the source will look NEW on the next
     * scan and be reprocessed — retention must sit beyond the source's own file lifetime. The default
     * implementation prunes nothing.
     */
    default int prune(long processedBefore, String sourceId) {
        return 0;
    }

    /**
     * Run backend maintenance on the ledger's storage (PIP-7 {@code db_maintenance} task): for a DB-backed
     * ledger, merge the WAL and reclaim space ({@code CHECKPOINT} / {@code VACUUM}) over the store's own
     * connection — DuckDB is single-writer, so maintenance must ride the live connection, never a second one.
     * Best-effort; the default is a no-op.
     */
    default void maintenance() {}

    /** Release resources (e.g. a DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
