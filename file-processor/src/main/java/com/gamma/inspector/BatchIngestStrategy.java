package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
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

    /**
     * The scratch directory for the per-batch temp DB <em>and</em> DuckDB's spill: explicit
     * {@code processing.duckdb.temp_directory}, else {@code dirs.temp} (on the data volume), else
     * {@code null} ⇒ fall back to the JVM temp dir. Routing scratch here is what keeps a huge
     * file's multi-hundred-GB temp data off a small system {@code /tmp}.
     */
    static String scratchDir(PipelineConfig cfg) {
        String explicit = cfg.duckdb().tempDirectory();
        if (explicit != null && !explicit.isBlank()) return explicit;
        String temp = cfg.dirs().temp();
        return (temp != null && !temp.isBlank()) ? temp : null;
    }

    /**
     * Create the per-batch temp DuckDB database in the resolved {@link #scratchDir scratch dir}
     * (data volume), falling back to {@code java.io.tmpdir} only when none is configured.
     */
    static File openTempDb(PipelineConfig cfg, String prefix) throws IOException {
        String dir = scratchDir(cfg);
        return dir == null ? DuckDbUtil.tempDbFile(prefix)
                           : DuckDbUtil.tempDbFile(prefix, Paths.get(dir));
    }

    /**
     * Apply the per-connection thread cap and any optional DuckDB resource controls
     * (memory limit, spill {@code temp_directory} = the scratch dir, spill size cap) to a freshly
     * opened worker connection.
     */
    static void configure(Connection conn, PipelineConfig cfg) throws SQLException {
        DuckDbUtil.applyWorkerThreads(conn, cfg.processing().duckdbThreads());
        DuckDbUtil.applyDuckDbSettings(conn,
                cfg.duckdb().memoryLimit(), scratchDir(cfg), cfg.duckdb().maxTempDirectorySize());
    }
}
