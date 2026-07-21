package com.gamma.job;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The {@code materialize} maintenance task (DAT-4 — Matrix materialization): persist a summary Derived
 * Table as a managed asset. Compiles this job's measure spec (BI-7 {@link MeasureCompiler} — the same
 * {@code measures}/{@code group_by} params as a dataset-scope report) over a source Dataset's trusted
 * relation, {@code COPY}s the result to Parquet under the space's data root, and registers/refreshes a
 * {@code dataset} component pointing at it — so the Matrix is queryable everywhere a Dataset is
 * (BI query, widgets, reports, alerts) with zero net-new read paths.
 *
 * <h3>Params</h3>
 * {@code dataset} (required source id) · {@code target} (required output dataset id = its store dir under
 * the data root) · {@code measures} ({@code count,sum(amount)} — omit for a raw SELECT-* snapshot) ·
 * {@code group_by} · {@code limit} (row cap, default 1,000,000).
 *
 * <h3>Refresh discipline (PIP-7's stage-and-atomic-swap)</h3>
 * The new snapshot lands invisibly ({@code *.parquet.tmp} — readers glob {@code *.parquet}); prior
 * snapshots are hidden ({@code → *.stale}) before the reveal (ATOMIC_MOVE), then deleted. A reader in the
 * swap window sees a briefly-empty Matrix rather than doubled rows — for a summary table, consistent
 * beats complete. A crash leaves only invisible {@code .tmp}/{@code .stale} files; the next run cleans them.
 */
final class MaterializeTask {

    private static final Logger log = LoggerFactory.getLogger(MaterializeTask.class);
    private static final Pattern SAFE_TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private MaterializeTask() {}

    static JobResult run(JobConfig cfg, String dataDir) throws Exception {
        long t0 = System.nanoTime();
        String wr = System.getProperty("assist.write.root");
        if (wr == null || wr.isBlank())
            throw new IllegalStateException("materialize needs -Dassist.write.root (the component registry)");
        if (dataDir == null || dataDir.isBlank())
            throw new IllegalStateException("materialize needs a data root (-Ddata.dir / space dataDir)");
        Path writeRoot = Path.of(wr);
        Path dataRoot = Path.of(dataDir);

        String source = cfg.require("dataset");
        String target = cfg.require("target");
        if (!SAFE_TARGET.matcher(target).matches() || target.contains(".."))
            throw new IllegalArgumentException("unsafe materialize target '" + target + "'");
        if (target.equals(source))
            throw new IllegalArgumentException("materialize target must differ from the source dataset");

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> dataset = store.get("dataset", source)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset '" + source + "'"));
        String relationSql = DatasetRelation.relationSql(dataset, dataRoot, new ViewStore(writeRoot.resolve("views")));
        String sql = compileSpec(cfg, source);

        Path outDir = dataRoot.resolve(target);
        Files.createDirectories(outDir);
        cleanLeftovers(outDir);   // a crashed prior run leaves only invisible .tmp/.stale files
        String snapshot = "matrix-" + System.currentTimeMillis() + ".parquet";
        Path tmp = outDir.resolve(snapshot + ".tmp");

        long rows;
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            st.execute("CREATE VIEW " + q(source) + " AS " + relationSql);
            st.execute("COPY (" + sql + ") TO " + sqlStr(tmp.toString().replace('\\', '/')) + " (FORMAT PARQUET)");
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM read_parquet(" + sqlStr(tmp.toString().replace('\\', '/')) + ")")) {
                rs.next();
                rows = rs.getLong(1);
            }
        }

        // Swap: hide prior snapshots, reveal the new one, drop the hidden ones (see class doc).
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
            try { Files.delete(p); } catch (IOException e) { log.warn("materialize: could not drop {}: {}", p, e.getMessage()); }
        }

        // Register/refresh the Matrix as a managed dataset asset (idempotent overwrite = the refresh).
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", target);
        content.put("physicalRef", target);
        content.put("description", "Materialized from dataset '" + source + "' (job '" + cfg.name() + "')");
        content.put("materialized", Map.of("from", source, "at", Instant.now().toString(), "rows", rows));
        store.write("dataset", target, content);

        return JobResult.ok("materialize: " + rows + " row(s) → " + outDir.resolve(snapshot)
                + " (dataset '" + target + "' refreshed)", (System.nanoTime() - t0) / 1_000_000L);
    }

    /** The spec-compiled SELECT (BI-7), or a raw snapshot when no measures/group_by are set. */
    private static String compileSpec(JobConfig cfg, String source) {
        int limit = Integer.parseInt(cfg.opt("limit", "1000000"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", source);
        List<Map<String, Object>> measures = new ArrayList<>();
        for (String m : split(cfg.opt("measures", ""))) {
            if ("count".equals(m)) { measures.add(Map.of("agg", "count")); continue; }
            int p = m.indexOf('(');
            if (p < 0 || !m.endsWith(")"))
                throw new IllegalArgumentException("measure must be count or agg(field), got '" + m + "'");
            measures.add(Map.of("agg", m.substring(0, p), "field", m.substring(p + 1, m.length() - 1)));
        }
        if (!measures.isEmpty()) body.put("measures", measures);
        List<String> groupBy = split(cfg.opt("group_by", ""));
        if (!groupBy.isEmpty()) body.put("groupBy", groupBy);
        body.put("limit", limit);
        if (measures.isEmpty() && groupBy.isEmpty())
            return "SELECT * FROM " + q(source) + " LIMIT " + limit;
        return MeasureCompiler.compile(MeasureCompiler.parse(body, limit, 100_000_000));
    }

    /** Clear invisible leftovers of a crashed prior run (never touches live {@code *.parquet}). */
    private static void cleanLeftovers(Path outDir) throws IOException {
        try (DirectoryStream<Path> junk = Files.newDirectoryStream(outDir, "*.{tmp,stale}")) {
            for (Path p : junk) {
                try { Files.delete(p); } catch (IOException e) { log.warn("materialize: stray {} not deletable: {}", p, e.getMessage()); }
            }
        }
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) for (String s : csv.split(",")) { String t = s.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
