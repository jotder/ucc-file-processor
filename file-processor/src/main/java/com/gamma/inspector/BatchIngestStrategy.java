package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.PipelineConfig;

import java.sql.Connection;
import java.sql.Statement;

/**
 * The ingest+transform+write half of processing one {@link Batch} — the part that
 * differs between the built-in CSV path and the plugin-ingester path. Each strategy
 * owns its own DuckDB connection lifecycle and produces an {@link IngestOutcome}
 * (survivors, outputs, lineage, per-member audit, status); the shared, path-agnostic
 * tail — commit (register → manifest → backup → markers) and audit — stays in
 * {@link BatchProcessor}, which selects the strategy and drives that tail.
 *
 * <p>This replaces the former {@code processCsv}/{@code processPlugin} god-methods:
 * {@link BatchProcessor#process} now dispatches polymorphically on
 * {@link PipelineConfig.Schemas#ingesterClass()} instead of branching inline.
 *
 * <p>Implementations are stateless and cheap to instantiate per batch.
 */
interface BatchIngestStrategy {

    /**
     * Ingest, transform, and write {@code batch}. Never throws: ingest failures are
     * captured into the returned outcome as {@code status = "FAILED"} so the batch
     * still flows through commit-skip + audit exactly as before.
     */
    IngestOutcome ingest(Batch batch, PipelineConfig cfg);

    // ── shared helpers ──────────────────────────────────────────────────────────

    /** Best-effort {@code DROP TABLE IF EXISTS}, swallowing any error. */
    static void dropTable(Connection conn, String table) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + table + "\"");
        } catch (Exception ignored) { }
    }

    /** A non-null message for an exception, falling back to its simple class name. */
    static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
