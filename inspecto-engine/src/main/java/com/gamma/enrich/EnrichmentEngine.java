package com.gamma.enrich;

import com.gamma.query.DecisionRuleApplier;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.JdbcRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Stage-2 enrichment engine: reads the Stage-1 multiplexer's partitioned output with
 * DuckDB, applies a configured columnar transform (joins against reference views +
 * aggregation/derivation), and writes the result as a new Hive-partitioned dataset —
 * <b>idempotently</b>, so event-driven and scheduled recomputes safely overwrite.
 *
 * <h3>Execution</h3>
 * <ol>
 *   <li>Register each {@code references[]} source as a DuckDB view (by name).</li>
 *   <li>Create the {@code input} view over the selected Stage-1 partitions
 *       ({@code read_parquet}/{@code read_csv} with {@code hive_partitioning}). When a
 *       partition filter is supplied, only those partitions are read — precise
 *       incremental recompute driven by a committed batch's lineage.</li>
 *   <li>Run the config's {@code transform} SQL into a temp table.</li>
 *   <li>Write it partitioned by {@code output.partitions} via {@link PartitionWriter}
 *       (no {@code __src_id} to exclude; {@code OVERWRITE_OR_IGNORE} makes re-runs safe).</li>
 * </ol>
 *
 * <p>Incremental scoping assumes each output partition derives from a bounded set of
 * input partitions (e.g. a daily KPI from a day's partition) — the common case. The
 * {@code transform} reads the view {@code input} and any reference views by name.
 */
public final class EnrichmentEngine {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentEngine.class);

    private EnrichmentEngine() {}

    /**
     * The outcome of one recompute: the written partition files and the total number of
     * output rows materialised (a cheap {@code COUNT(*)} on the transform result, for
     * run-level audit/observability).
     */
    public record Result(List<PartitionOutput> outputs, long totalRows) {}

    /** Full recompute over all input partitions. */
    public static List<PartitionOutput> run(EnrichmentConfig cfg) throws Exception {
        return run(cfg, null);
    }

    /**
     * Recompute enrichment output, returning the written partition files only. Equivalent
     * to {@link #runResult(EnrichmentConfig, List)}{@code .outputs()}.
     *
     * @param partitionFilter when non-empty, restricts the {@code input} view to these
     *                        partitions (each map is partitionColumn → value, AND-ed
     *                        within a map, OR-ed across maps). {@code null}/empty = full.
     * @return one {@link PartitionOutput} per written partition file
     */
    public static List<PartitionOutput> run(EnrichmentConfig cfg,
                                            List<Map<String, String>> partitionFilter) throws Exception {
        return runResult(cfg, partitionFilter).outputs();
    }

    /**
     * Recompute enrichment output and report the written partition files <em>and</em> the
     * total output row count — the richer form the orchestrator uses to write run-level
     * audit/lineage.
     *
     * @see #run(EnrichmentConfig, List)
     */
    public static Result runResult(EnrichmentConfig cfg,
                                   List<Map<String, String>> partitionFilter) throws Exception {
        return runResult(cfg, partitionFilter, List.of());
    }

    /**
     * As {@link #runResult(EnrichmentConfig, List)}, additionally supplying the loaded pipelines so
     * a by-name reference ({@code references.<name>.ref:} → a {@code produces: reference} pipeline)
     * can resolve to that pipeline's partitioned output. Callers without a pipeline context pass an
     * empty list — a by-name reference then fails with a clear error (use {@code path:} instead).
     */
    public static Result runResult(EnrichmentConfig cfg, List<Map<String, String>> partitionFilter,
                                   List<PipelineConfig> pipelines) throws Exception {
        return runResult(cfg, partitionFilter, pipelines, List.of(), null);
    }

    /**
     * As {@link #runResult(EnrichmentConfig, List, List)}, additionally naming the unit of work so
     * Decision Rules can check the output: {@code ruleTargets} are extra {@code targetType: job}
     * names to match (the wrapping job's — the enrichment's own name always matches), and
     * {@code runId} correlates each {@code decision-rule.applied} signal with the run's audit row.
     */
    public static Result runResult(EnrichmentConfig cfg, List<Map<String, String>> partitionFilter,
                                   List<PipelineConfig> pipelines, List<String> ruleTargets,
                                   String runId) throws Exception {
        File db = DuckDbUtil.tempDbFile("enrich_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            // Enrichment has no per-config processing.duckdb section; honour the global -D caps so this
            // scratch connection isn't uncapped (defaults ≈ 80% RAM) while the batch path is capped.
            DuckDbUtil.applyGlobalDuckDbSettings(conn);

            // 1. reference views
            for (EnrichmentConfig.Reference r : cfg.references()) {
                st.execute("CREATE VIEW \"" + r.name() + "\" AS SELECT * FROM "
                        + referenceReader(r, pipelines));
            }

            // 2. input view over the selected Stage-1 partitions
            String inputGlob = cfg.input().database().replace("\\", "/")
                    + "/**/*." + SqlViews.ext(cfg.input().format());
            String inputSql = "SELECT * FROM " + SqlViews.reader(cfg.input().format(), inputGlob, true);
            String where = buildFilter(partitionFilter);
            if (!where.isEmpty()) inputSql += " WHERE " + where;
            st.execute("CREATE VIEW input AS " + inputSql);

            // 3. transform → temp table
            st.execute("CREATE TABLE __enriched AS " + cfg.transformSql());

            // 3b. Decision Rules check the enriched output before it is written (tag/route/
            //     quarantine/drop): matched as targetType: job by the enrichment's own name or a
            //     wrapping job's; route lands Hive-partitioned under output.database/<destination>,
            //     record-quarantine beside the output as <output.database>_quarantine (the
            //     EnrichmentAuditWriter sibling-suffix convention).
            String baseName = cfg.name().toLowerCase().replace(' ', '_');
            DecisionRuleApplier.Result applied = DecisionRuleApplier.apply(conn, "__enriched",
                    DecisionRuleApplier.Subject.enrichment(cfg.name(), ruleTargets, runId),
                    cfg.output().database() + "_quarantine", baseName,
                    (c, routedTable, dest) -> new DecisionRuleApplier.Result(
                            PartitionWriter.write(c, routedTable,
                                    Paths.get(cfg.output().database(), dest).toString(),
                                    cfg.output().format(), cfg.output().compression(),
                                    baseName, cfg.output().partitions(), List.of()),
                            List.of()));

            // 4. idempotent partitioned write (no __src_id to exclude)
            List<PartitionOutput> outputs = PartitionWriter.write(
                    conn, "__enriched",
                    cfg.output().database(), cfg.output().format(), cfg.output().compression(),
                    baseName, cfg.output().partitions(), List.of());
            if (!applied.outputs().isEmpty()) {
                List<PartitionOutput> all = new ArrayList<>(applied.outputs());
                all.addAll(outputs);
                outputs = all;
            }

            long totalRows = countRows(st, "__enriched");
            log.info("[ENRICH] {}: {} → {} partition file(s), {} row(s){}",
                    cfg.name(),
                    (partitionFilter == null || partitionFilter.isEmpty())
                            ? "full" : partitionFilter.size() + " input partition(s)",
                    outputs.size(), totalRows,
                    cfg.references().isEmpty() ? "" : "  refs=" + cfg.references().size());
            return new Result(outputs, totalRows);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /**
     * A bounded, non-persisting <b>preview</b> of an enrichment (the onboarding "Validated" state): seed the
     * {@code input} view from an in-memory {@code sample} — a preview has no Stage-1 output on disk — register
     * the reference views exactly as {@link #runResult} does, materialise the {@code transform}, and read back
     * the first rows. Nothing is written to {@code output.database}; the scratch DuckDB is deleted on the way
     * out. Sample values seed as {@code VARCHAR} (the preview contract, shared with the parsing/schema
     * previews) — the transform casts as needed, just as in production.
     *
     * @throws IllegalArgumentException on an empty sample; a bad transform or an unresolvable reference throws
     *         (the caller maps it to a 422 — a preview surfaces exactly the compute error a run would hit).
     */
    public static Preview preview(EnrichmentConfig cfg, List<Map<String, Object>> sample,
                                  List<PipelineConfig> pipelines, int limit) throws Exception {
        List<Map<String, Object>> rows = (sample == null) ? List.of() : sample;
        if (rows.isEmpty()) throw new IllegalArgumentException("enrichment preview needs a non-empty sample");
        int cap = Math.max(1, limit);
        File db = DuckDbUtil.tempDbFile("enrich_preview_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            // 1. reference views — resolved exactly as a run (real data, bounded by the transform's own join)
            for (EnrichmentConfig.Reference r : cfg.references())
                st.execute("CREATE VIEW \"" + r.name() + "\" AS SELECT * FROM " + referenceReader(r, pipelines));
            // 2. input seeded from the caller's sample (not the Stage-1 glob)
            seedInput(conn, rows);
            // 3. transform → temp table
            st.execute("CREATE TABLE __enriched AS " + cfg.transformSql());
            // read back a bounded page (limit+1 detects truncation)
            List<String> columns;
            List<Map<String, Object>> out;
            try (ResultSet rs = st.executeQuery("SELECT * FROM __enriched LIMIT " + (cap + 1))) {
                columns = JdbcRows.columnLabels(rs);
                out = JdbcRows.toMaps(rs);
            }
            boolean truncated = out.size() > cap;
            if (truncated) out = new ArrayList<>(out.subList(0, cap));
            return new Preview(columns, out, truncated);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Seed the {@code input} table (all VARCHAR over the union of the sample's keys) from an in-memory sample. */
    private static void seedInput(Connection conn, List<Map<String, Object>> rows) throws SQLException {
        LinkedHashSet<String> colSet = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) colSet.addAll(r.keySet());
        if (colSet.isEmpty()) throw new IllegalArgumentException("enrichment preview sample has no columns");
        List<String> cols = new ArrayList<>(colSet);
        StringBuilder create = new StringBuilder("CREATE TABLE input (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) create.append(", ");
            create.append('"').append(cols.get(i).replace("\"", "\"\"")).append("\" VARCHAR");
        }
        create.append(")");
        try (Statement st = conn.createStatement()) { st.execute(create.toString()); }
        StringBuilder ins = new StringBuilder("INSERT INTO input VALUES (");
        for (int i = 0; i < cols.size(); i++) ins.append(i > 0 ? ",?" : "?");
        ins.append(")");
        try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < cols.size(); i++) {
                    Object v = row.get(cols.get(i));
                    ps.setString(i + 1, v == null ? null : v.toString());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** A bounded enrichment preview: the enriched columns, the first rows, and whether more rows exist. */
    public record Preview(List<String> columns, List<Map<String, Object>> rows, boolean truncated) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("columns", columns);
            m.put("rows", rows);
            m.put("truncated", truncated);
            return m;
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * The table-function expression for one reference view: a direct {@code path} reads the file
     * as-is; a by-name {@code ref} resolves to the declaring {@code produces: reference} pipeline's
     * Hive-partitioned Stage-1 output (its {@code dirs.database} glob, its output format).
     */
    private static String referenceReader(EnrichmentConfig.Reference r, List<PipelineConfig> pipelines) {
        if (!r.byName()) return SqlViews.reader(r.format(), r.path(), false);
        PipelineConfig p = (pipelines == null ? List.<PipelineConfig>of() : pipelines).stream()
                .filter(c -> c.identity().pipelineName().equals(r.ref()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "reference '" + r.name() + "' binds ref: '" + r.ref()
                                + "' but no such pipeline is loaded (by-name references need the "
                                + "service's pipeline context; use path: for a plain file)"));
        if (!p.producesReference())
            throw new IllegalArgumentException("reference '" + r.name() + "' binds ref: '" + r.ref()
                    + "' but that pipeline does not declare 'produces: reference'");
        String format = (p.output() == null || p.output().format() == null)
                ? "CSV" : p.output().format().toUpperCase(Locale.ROOT);
        String glob = p.dirs().database() + "/**/*." + SqlViews.ext(format);
        return SqlViews.reader(format, glob, true);
    }

    /** {@code COUNT(*)} of the materialised transform result. */
    private static long countRows(Statement st, String table) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Build {@code (c1='v1' AND c2='v2') OR (...)} from the partition filter. */
    private static String buildFilter(List<Map<String, String>> filter) {
        if (filter == null || filter.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < filter.size(); i++) {
            Map<String, String> m = filter.get(i);
            if (m.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" OR ");
            sb.append('(');
            int j = 0;
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (j++ > 0) sb.append(" AND ");
                sb.append('"').append(e.getKey()).append("\"='")
                  .append(e.getValue().replace("'", "''")).append('\'');
            }
            sb.append(')');
        }
        return sb.toString();
    }
}
