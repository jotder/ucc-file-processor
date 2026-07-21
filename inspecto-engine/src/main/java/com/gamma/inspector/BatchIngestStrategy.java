package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.CsvIngester;
import com.gamma.etl.DecisionRuleApplier;
import com.gamma.etl.LineageCollector;
import com.gamma.etl.LineageRow;
import com.gamma.etl.PartitionDef;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

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

    /** Best-effort {@code DROP VIEW IF EXISTS}, swallowing any error. */
    static void dropView(Connection conn, String view) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS \"" + view + "\"");
        } catch (Exception ignored) { }
    }

    /** A non-null message for an exception, falling back to its simple class name. */
    static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ── shared ingest-tail helpers (used by both strategies) ─────────────────────

    /** Partition columns from a schema ({@code year/month/day} when none are declared). */
    static List<String> partitionColumns(Map<String, Object> schema) {
        List<PartitionDef> defs = PartitionDef.fromSchema(schema);
        return defs.isEmpty() ? List.of("year", "month", "day") : PartitionDef.columnNames(defs);
    }

    /** The result of one partitioned write: output files plus their lineage matrix. */
    record Written(List<PartitionOutput> outputs, List<LineageRow> lineage) {}

    /**
     * The shared tail of every ingest path: apply the pipeline's Decision Rules to {@code table}
     * ({@link DecisionRuleApplier} — exact no-op when none are authored), then write it
     * Hive-partitioned under {@code dbDir} and collect the input→output lineage matrix over the same
     * partitions. Routed rows contribute their own outputs + lineage.
     */
    static Written writeAndTrace(Connection conn, String table, List<String> partCols,
                                 PipelineConfig cfg, String dbDir, String baseName,
                                 String batchId, Map<Integer, String> srcIdToFile) throws Exception {
        DecisionRuleApplier.Result applied = DecisionRuleApplier.apply(
                conn, table, cfg, dbDir, baseName, partCols, batchId, srcIdToFile);
        List<PartitionOutput> mainOut = PartitionWriter.write(conn, table, dbDir,
                cfg.output().format(), cfg.output().compression(), baseName, partCols);
        List<LineageRow> mainLineage = LineageCollector.collect(
                conn, table, batchId, srcIdToFile, mainOut, partCols);
        if (applied.outputs().isEmpty() && applied.lineage().isEmpty())
            return new Written(mainOut, mainLineage);
        List<PartitionOutput> outputs = new java.util.ArrayList<>(applied.outputs());
        outputs.addAll(mainOut);
        List<LineageRow> lineage = new java.util.ArrayList<>(applied.lineage());
        lineage.addAll(mainLineage);
        return new Written(outputs, lineage);
    }

    /**
     * Consolidated-output base name: a single surviving member keeps its file stem (legacy
     * {@code <basename>_out.<ext>} naming); a multi-member batch is named by its batch id.
     */
    static String consolidatedBaseName(List<Batch.Member> survivors, Batch batch) {
        return survivors.size() == 1
                ? CsvIngester.stripExtensions(survivors.get(0).file().getName())
                : batch.batchId();
    }

    /** Output database dir for a batch: {@code dirs.database}, or a {@code table}-named subdir when set. */
    static String databaseDir(Batch batch, PipelineConfig cfg) {
        return (batch.table() != null && !batch.table().isBlank())
                ? Paths.get(cfg.dirs().database(), batch.table()).toString()
                : cfg.dirs().database();
    }

    /** A lazy {@code SELECT * … UNION ALL …} over the given relations (tables or views). */
    static String unionAll(List<String> relations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relations.size(); i++) {
            if (i > 0) sb.append(" UNION ALL ");
            sb.append("SELECT * FROM \"").append(relations.get(i)).append('"');
        }
        return sb.toString();
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
     *
     * <p>The thread cap is resolved through {@link DuckDbUtil#effectiveWorkerThreads} so that the
     * default ({@code duckdb_threads = 0}) auto-divides the host's cores among the concurrent
     * batches ({@code processing.threads}) instead of letting every batch connection grab all cores
     * — the latter oversubscribes the CPU when more than one batch runs at a time.
     */
    static void configure(Connection conn, PipelineConfig cfg) throws SQLException {
        int effectiveThreads = DuckDbUtil.effectiveWorkerThreads(
                cfg.processing().duckdbThreads(),
                cfg.processing().threads(),
                Runtime.getRuntime().availableProcessors());
        DuckDbUtil.applyWorkerThreads(conn, effectiveThreads);
        DuckDbUtil.applyDuckDbSettings(conn,
                cfg.duckdb().memoryLimit(), scratchDir(cfg), cfg.duckdb().maxTempDirectorySize());
    }
}
