package com.gamma.inspector;

import com.gamma.etl.Batch;
import com.gamma.etl.CsvIngester;
import com.gamma.query.DecisionRuleApplier;
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

        // Reference Phase-2 P1: a `produces: reference` pipeline with `load: upsert` writes an
        // append-only versioned store — each batch stamps system columns (__key_hash/__valid_from/
        // __op/__batch_id), folds out within-batch key duplicates, and reveals under a batch-unique
        // file stem so prior versions survive (latest-version-wins is derived at read time by the
        // enrichment current view). `load: replace` (the default) is untouched — plain overwrite.
        String writeTable = table;
        String writeBase  = baseName;
        if (cfg.producesReference() && cfg.reference().load() == PipelineConfig.Load.UPSERT) {
            writeTable = "__ref_versioned";
            stampReferenceVersions(conn, table, writeTable, cfg.reference().key(), batchId);
            writeBase = baseName + "__v_" + batchId;   // batch-unique ⇒ append, never overwrite
        }
        List<PartitionOutput> mainOut = PartitionWriter.write(conn, writeTable, dbDir,
                cfg.output().format(), cfg.output().compression(), writeBase, partCols);
        List<LineageRow> mainLineage = LineageCollector.collect(
                conn, writeTable, batchId, srcIdToFile, mainOut, partCols);
        if (applied.outputs().isEmpty() && applied.lineage().isEmpty())
            return new Written(mainOut, mainLineage);
        List<PartitionOutput> outputs = new java.util.ArrayList<>(applied.outputs());
        outputs.addAll(mainOut);
        List<LineageRow> lineage = new java.util.ArrayList<>(applied.lineage());
        lineage.addAll(mainLineage);
        return new Written(outputs, lineage);
    }

    /**
     * Reference Phase-2 P1 (design (c) — append-only, latest-version-wins): materialise {@code dst}
     * from {@code src} with the reference system columns appended and within-batch key duplicates
     * folded out. Each surviving row carries {@code __key_hash} (canonical hash of the declared
     * {@code reference.key} columns), {@code __valid_from} (load instant), {@code __op} ({@code 'upsert'}
     * on the ingest path — {@code 'delete'} tombstones are honoured by the read-side current view but
     * are not produced here in P1) and {@code __batch_id}. The lineage tag {@code __src_id} is kept so
     * {@link PartitionWriter}'s default exclude and {@link LineageCollector} keep working unchanged.
     *
     * <p>Within-batch dedup keeps one row per {@code __key_hash} ({@code QUALIFY row_number() = 1}); a
     * batch that delivers the same key twice writes a single version. The winner is arbitrary in P1
     * (no {@code order_by} column yet — the plan's optional latest-by-column is a later refinement).
     */
    static void stampReferenceVersions(Connection conn, String src, String dst,
                                       List<String> keyCols, String batchId) throws SQLException {
        if (keyCols == null || keyCols.isEmpty())
            throw new IllegalStateException(
                    "reference load 'upsert' requires a non-empty reference.key (config validation should "
                    + "have rejected this pipeline before execution)");
        StringBuilder hash = new StringBuilder("md5(concat_ws(chr(31)");
        for (String k : keyCols)
            hash.append(", COALESCE(CAST(\"").append(k.replace("\"", "\"\"")).append("\" AS VARCHAR), '')");
        hash.append("))");
        String hashExpr = hash.toString();
        String sql = "CREATE TABLE \"" + dst + "\" AS SELECT *, "
                + hashExpr + " AS __key_hash, "
                + "now()::TIMESTAMP AS __valid_from, "
                + "'upsert' AS __op, "
                + "'" + batchId.replace("'", "''") + "' AS __batch_id "
                + "FROM \"" + src + "\" "
                + "QUALIFY row_number() OVER (PARTITION BY " + hashExpr + ") = 1";
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS \"" + dst + "\"");
            st.execute(sql);
        }
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
        // Per-config value wins; else the global -Dprocessing.duckdb.* fallback, so one operator knob
        // caps this path uniformly with the (config-less) flow-job and enrichment scratch connections.
        DuckDbUtil.applyDuckDbSettings(conn,
                DuckDbUtil.globalOr(cfg.duckdb().memoryLimit(), DuckDbUtil.PROP_MEMORY_LIMIT),
                scratchDir(cfg),
                DuckDbUtil.globalOr(cfg.duckdb().maxTempDirectorySize(), DuckDbUtil.PROP_MAX_TEMP_DIRECTORY_SIZE));
    }
}
