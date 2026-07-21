package com.gamma.job;

import com.gamma.pipeline.DecisionRuleApplier;
import com.gamma.etl.PartitionOutput;
import com.gamma.pipeline.exec.SourceStoreReader;
import com.gamma.signal.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code sql.template} Job Type (job-framework §15.1, P3b) — the first real Run Artifact producer.
 * Runs an authored SQL template (its {@code $name} tokens resolved by the framework, §7.2) against source
 * Datasets registered as DuckDB views over their at-rest Parquet, materializes the result to a snapshot
 * Parquet Dataset under the space's data root, and records a queryable Run Artifact ({@code ctx.artifacts()
 * .dataset(...)}) so downstream Jobs can chain on it via {@code $upstream(...)}.
 *
 * <h3>Params</h3>
 * {@code sql} (required — the template; its {@code $name} tokens <i>are</i> the parameter contract, scanned
 * by {@link SqlParamScanner}) · {@code sink_dataset} (required — output store dir under the data root) ·
 * {@code sources} (optional CSV of source store names, each registered as a same-named view over
 * {@code <dataDir>/<store>/**}{@code /*.parquet}).
 *
 * <h3>Refresh discipline</h3>
 * Mirrors {@link MaterializeTask}'s stage-and-atomic-swap: the new snapshot lands invisibly
 * ({@code *.parquet.tmp}), prior snapshots are hidden ({@code → *.stale}) before the atomic reveal, then
 * deleted — a reader in the swap window sees a briefly-empty Dataset, never doubled rows.
 *
 * <p>Follows the built-in convention of constructor-injected {@code dataDir} (as {@code ReportJob}/
 * {@code MaintenanceJob}) rather than a {@code JobContext} data-plane façade — DuckDB is opened via the
 * shared JDBC driver and the space clock is {@link Instant#now()}, exactly as the other jobs do.
 */
final class SqlTemplateJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SqlTemplateJob.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final String OUT_TABLE = "__sql_template_out";

    private final JobConfig cfg;
    private final String dataDir;

    SqlTemplateJob(JobConfig cfg, String dataDir) {
        this.cfg = cfg;
        this.dataDir = dataDir;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "sql.template"; }

    /** {@code sql.template} always runs with a {@link JobContext} (it reads resolved params, records artifacts). */
    @Override public JobResult run() {
        throw new UnsupportedOperationException("sql.template requires a JobContext");
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        if (dataDir == null || dataDir.isBlank())
            throw new IllegalStateException("sql.template needs a data root (space dataDir)");

        String sink = safe(cfg.require("sink_dataset"), "sink_dataset");
        String sql = SqlParamScanner.substitute(cfg.require("sql"), ctx.params());
        List<String> sources = splitCsv(cfg.opt("sources", ""));

        Path outDir = Path.of(dataDir).resolve(sink);
        Files.createDirectories(outDir);
        cleanLeftovers(outDir);   // a crashed prior run leaves only invisible .tmp/.stale files
        String snapshot = "sql-" + System.currentTimeMillis() + ".parquet";
        Path tmp = outDir.resolve(snapshot + ".tmp");

        long rows;
        ResultSetMeta meta;
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            for (String store : sources)
                SourceStoreReader.registerView(conn, safe(store, "source store"), dataDir, store, "PARQUET");
            st.execute("CREATE TABLE " + OUT_TABLE + " AS " + sql);
            // Decision Rules targeting this job check the materialized result before it becomes the
            // snapshot (tag/route/quarantine/drop): route lands as a snapshot Parquet Dataset under
            // <dataDir>/<destination> (same swap-in discipline as the sink), record-quarantine under
            // <dataDir>/.quarantine (dot-dirs are invisible to store globs, like .staging).
            DecisionRuleApplier.apply(conn, OUT_TABLE,
                    DecisionRuleApplier.Subject.job(cfg.name(), ctx.runId()),
                    Path.of(dataDir).resolve(".quarantine").toString(), sink,
                    (c, routedTable, dest) -> {
                        Path destDir = Path.of(dataDir).resolve(dest);
                        Files.createDirectories(destDir);
                        cleanLeftovers(destDir);
                        String routedSnap = "sql-" + System.currentTimeMillis() + ".parquet";
                        Path routedTmp = destDir.resolve(routedSnap + ".tmp");
                        try (Statement rst = c.createStatement()) {
                            rst.execute("COPY \"" + routedTable + "\" TO "
                                    + sqlStr(routedTmp.toString().replace('\\', '/')) + " (FORMAT PARQUET)");
                        }
                        swapIn(destDir, routedTmp, routedSnap);
                        Path revealed = destDir.resolve(routedSnap);
                        return new DecisionRuleApplier.Result(
                                List.of(new PartitionOutput("", revealed.toString(), Files.size(revealed))),
                                List.of());
                    });
            meta = resultSetMeta(conn);
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + OUT_TABLE)) {
                rs.next();
                rows = rs.getLong(1);
            }
            st.execute("COPY " + OUT_TABLE + " TO " + sqlStr(tmp.toString().replace('\\', '/')) + " (FORMAT PARQUET)");
        }

        swapIn(outDir, tmp, snapshot);
        ctx.artifacts().dataset("output", sink, meta, rows, Instant.now());
        ctx.signals().emit("job.dataset.produced", Severity.INFO, Map.of("dataset", sink, "rows", rows));
        ctx.log().info("wrote derived dataset", "sink", sink, "rows", rows, "sources", sources);
        return JobResult.ok("sql.template: " + rows + " row(s) → dataset '" + sink + "'",
                (System.nanoTime() - t0) / 1_000_000L);
    }

    /** The output shape from the materialized table's JDBC metadata (drives $upstream + BI, §10). */
    private static ResultSetMeta resultSetMeta(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + OUT_TABLE + " LIMIT 0")) {
            ResultSetMetaData md = rs.getMetaData();
            List<ResultSetMeta.Column> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String sqlType = md.getColumnTypeName(i);
                cols.add(new ResultSetMeta.Column(md.getColumnLabel(i), sqlType, roleOf(sqlType)));
            }
            return new ResultSetMeta(cols);
        }
    }

    /** Best-effort BI role from the SQL type name: temporal / numeric-measure / else dimension. */
    private static ResultSetMeta.Role roleOf(String sqlType) {
        String t = sqlType == null ? "" : sqlType.toUpperCase(Locale.ROOT);
        if (t.startsWith("DATE") || t.startsWith("TIME")) return ResultSetMeta.Role.TEMPORAL;
        if (t.contains("INT") || t.startsWith("DEC") || t.startsWith("NUMERIC")
                || t.startsWith("DOUBLE") || t.startsWith("FLOAT") || t.startsWith("REAL") || t.startsWith("HUGEINT"))
            return ResultSetMeta.Role.MEASURE;
        return ResultSetMeta.Role.DIMENSION;
    }

    /** Hide prior snapshots, reveal the new one atomically, drop the hidden ones (see class doc). */
    private static void swapIn(Path outDir, Path tmp, String snapshot) throws IOException {
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> old = Files.newDirectoryStream(outDir, "*.parquet")) {
            for (Path p : old) {
                Path hidden = p.resolveSibling(p.getFileName() + ".stale");
                Files.move(p, hidden, StandardCopyOption.ATOMIC_MOVE);
                stale.add(hidden);
            }
        }
        Files.move(tmp, outDir.resolve(snapshot), StandardCopyOption.ATOMIC_MOVE);
        for (Path p : stale) {
            try { Files.delete(p); } catch (IOException e) { log.warn("sql.template: could not drop {}: {}", p, e.getMessage()); }
        }
    }

    /** Clear invisible leftovers of a crashed prior run (never touches live {@code *.parquet}). */
    private static void cleanLeftovers(Path outDir) throws IOException {
        try (DirectoryStream<Path> junk = Files.newDirectoryStream(outDir, "*.{tmp,stale}")) {
            for (Path p : junk) {
                try { Files.delete(p); } catch (IOException e) { log.warn("sql.template: stray {} not deletable: {}", p, e.getMessage()); }
            }
        }
    }

    private static String safe(String id, String what) {
        if (id == null || !SAFE_ID.matcher(id).matches() || id.contains(".."))
            throw new IllegalArgumentException("unsafe " + what + " '" + id + "'");
        return id;
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) for (String s : csv.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
