package com.gamma.query;

import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dataset reconciliation execution (DAT-7; design {@code docs/superpower/reconciliation-board-design.md}).
 * Compares two Datasets at the <b>key-column grain</b> entirely inside an ephemeral DuckDB sandbox — each
 * side's trusted {@link DatasetRelation} SQL is registered as a view, then <b>server-built</b> SQL (never
 * caller-authored — there is no {@code SqlGuard} surface here) computes:
 * <ul>
 *   <li>{@link #run} — the Board's grain rows: one {@code FULL OUTER JOIN} of the two grouped sides
 *       (NULL key values match via {@code IS NOT DISTINCT FROM}, mirroring the UI engine's
 *       {@code keyOf(null) == ''} rule), plus exact per-side totals and a one-pass Break summary that
 *       stays exact even when the grain rows truncate;</li>
 *   <li>{@link #breaks} — the three Break sets at the same grain ({@code missing_right} = anchor-only,
 *       {@code missing_left} = other-only, {@code value_break} = matched outside tolerance), paged and
 *       optionally scoped to a Board dimension path.</li>
 * </ul>
 * Tolerance semantics are the SQL port of the UI's {@code withinTolerance} (reconciliation-types.ts,
 * locked 2026-07-03): {@code exact} | {@code absolute} | {@code percent} — percent is left-relative and a
 * zero left side requires exact equality. Row-level Break lifecycle (auto-close, preserved resolutions)
 * stays client-side per the C9 review-sheet contract; this service is stateless compute.
 */
public final class ReconService {

    private ReconService() {}

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Wire name of the implicit COUNT(*) measure (also the internal presence marker — never NULL for a group). */
    public static final String RECORDS = "__records";

    // ── spec ────────────────────────────────────────────────────────────────────────

    /**
     * One side of the reconciliation. {@code relationSql} is the trusted server-built relation
     * ({@link DatasetRelation}); {@code columnMap} maps unified column name → this side's physical column
     * (absent = same name); {@code filter} is a raw row-level predicate over the side's physical columns,
     * already {@link ExpressionGuard}-checked by {@link Spec#of}.
     */
    public record Side(String datasetId, String relationSql, Map<String, String> columnMap, String filter) {}

    /** One compared Measure: {@code agg} = sum|count; tolerance is the record-grain break truth (UI parity). */
    public record Measure(String name, String agg, String toleranceType, double tolerance) {}

    /** A validated reconciliation spec — construct via {@link #of}. Side 0 is the anchor ("a"). */
    public record Spec(List<Side> sides, List<String> keyColumns, List<Measure> measures, boolean includeRecordCount) {

        /** Validate + normalize; throws {@link IllegalArgumentException} on anything unusable (→ 422). */
        public static Spec of(List<Side> sides, List<String> keyColumns, List<Measure> measures,
                              boolean includeRecordCount) {
            if (sides == null || sides.size() != 2)
                throw new IllegalArgumentException("expected exactly 2 datasets (N-way reconciliation ships later), got "
                        + (sides == null ? 0 : sides.size()));
            if (keyColumns == null || keyColumns.isEmpty())
                throw new IllegalArgumentException("at least one key column is required");
            for (String k : keyColumns) safeIdent(k, "key column");
            List<Measure> ms = measures == null ? List.of() : measures;
            for (Measure m : ms) {
                safeIdent(m.name(), "compare column");
                if (RECORDS.equals(m.name()))
                    throw new IllegalArgumentException("'" + RECORDS + "' is reserved for the implicit record count");
                if (keyColumns.contains(m.name()))
                    throw new IllegalArgumentException("column '" + m.name() + "' cannot be both a key and a compare column");
                if (!"sum".equals(m.agg()) && !"count".equals(m.agg()))
                    throw new IllegalArgumentException("compare column '" + m.name() + "': agg must be sum|count, got '" + m.agg() + "'");
                if (!"exact".equals(m.toleranceType()) && !"absolute".equals(m.toleranceType())
                        && !"percent".equals(m.toleranceType()))
                    throw new IllegalArgumentException("compare column '" + m.name()
                            + "': toleranceType must be exact|absolute|percent, got '" + m.toleranceType() + "'");
                if (!(m.tolerance() >= 0))
                    throw new IllegalArgumentException("compare column '" + m.name() + "': tolerance must be >= 0");
            }
            if (ms.isEmpty() && !includeRecordCount)
                throw new IllegalArgumentException("nothing to compare: no compare columns and record count disabled");
            for (Side s : sides) {
                if (s.relationSql() == null || s.relationSql().isBlank())
                    throw new IllegalArgumentException("side '" + s.datasetId() + "' has no relation SQL");
                if (s.columnMap() != null)
                    for (Map.Entry<String, String> e : s.columnMap().entrySet()) {
                        safeIdent(e.getKey(), "columnMap name");
                        safeIdent(e.getValue(), "columnMap target");
                    }
                if (s.filter() != null) ExpressionGuard.check(s.filter());
            }
            return new Spec(List.copyOf(sides), List.copyOf(keyColumns), List.copyOf(ms), includeRecordCount);
        }

        /** This side's physical column for a unified column name. */
        String physical(int side, String unified) {
            Map<String, String> map = sides.get(side).columnMap();
            return map == null ? unified : map.getOrDefault(unified, unified);
        }
    }

    // ── results ─────────────────────────────────────────────────────────────────────

    /**
     * The Board payload: grain rows shaped {@code {key:{dim:v}, a:{measure:v}, b:{…}, inA, inB}}, exact
     * per-side {@code totals} ({@code {a:{…}, b:{…}}}), and the exact Break {@code summary}
     * ({@code {groups, matchedKeys, byType:{missing_left, missing_right, value_break}}}).
     */
    public record RunResult(List<Map<String, Object>> rows, Map<String, Object> totals,
                            Map<String, Object> summary, boolean truncated, long elapsedMs) {}

    /** One paged Break set: rows shaped like {@link RunResult} rows (single side for the missing sets). */
    public record BreakSet(List<Map<String, Object>> rows, int rowCount, boolean truncated) {}

    // ── execution ───────────────────────────────────────────────────────────────────

    /** Views the two sides register as — index-stable so every builder can reference them. */
    private static final String[] VIEWS = {"__recon_a", "__recon_b"};
    private static final String[] WIRE_SIDES = {"a", "b"};

    public static RunResult run(Spec spec, int limit) throws SQLException, IOException {
        long t0 = System.nanoTime();
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = registerSides(sandbox, spec);

            List<Map<String, Object>> grain = select(conn, grainSql(spec, limit));
            boolean truncated = grain.size() > limit;
            if (truncated) grain = grain.subList(0, limit);

            List<Map<String, Object>> rows = new ArrayList<>(grain.size());
            for (Map<String, Object> r : grain) rows.add(shapeRow(spec, r));

            Map<String, Object> totals = new LinkedHashMap<>();
            for (int i = 0; i < 2; i++)
                totals.put(WIRE_SIDES[i], measuresOf(spec, select(conn, totalsSql(spec, i)).get(0), null));

            Map<String, Object> s = select(conn, summarySql(spec)).get(0);
            Map<String, Object> byType = new LinkedHashMap<>();
            byType.put("missing_left", s.get("missing_left"));
            byType.put("missing_right", s.get("missing_right"));
            byType.put("value_break", s.get("value_break"));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("groups", s.get("groups"));
            summary.put("matchedKeys", s.get("matched"));
            summary.put("byType", byType);

            return new RunResult(rows, totals, summary, truncated, (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /**
     * The Break sets at the recon grain, optionally scoped to a Board dimension {@code path}
     * (unified key column → value, compared as VARCHAR). {@code type} filters to one set, or
     * {@code null} returns all three.
     */
    public static Map<String, BreakSet> breaks(Spec spec, Map<String, String> path, String type,
                                               int limit, int offset) throws SQLException, IOException {
        if (path != null)
            for (String dim : path.keySet())
                if (!spec.keyColumns().contains(dim))
                    throw new IllegalArgumentException("path column '" + dim + "' is not a key column");
        if (type != null && !type.equals("missing_left") && !type.equals("missing_right") && !type.equals("value_break"))
            throw new IllegalArgumentException("type must be missing_left|missing_right|value_break, got '" + type + "'");

        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = registerSides(sandbox, spec);
            Map<String, BreakSet> out = new LinkedHashMap<>();
            if (type == null || type.equals("missing_right"))
                out.put("missing_right", breakSet(conn, spec, missingSql(spec, 0, path, limit, offset), 0, limit));
            if (type == null || type.equals("missing_left"))
                out.put("missing_left", breakSet(conn, spec, missingSql(spec, 1, path, limit, offset), 1, limit));
            if (type == null || type.equals("value_break"))
                out.put("value_break", spec.measures().isEmpty()
                        ? new BreakSet(List.of(), 0, false)
                        : breakSet(conn, spec, valueBreaksSql(spec, path, limit, offset), -1, limit));
            return out;
        }
    }

    /** Per-side column inventory ({@code SELECT * … LIMIT 0} metadata) + cross-side auto-matches. */
    public static Map<String, Object> columns(List<Side> sides) throws SQLException, IOException {
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = sandbox.connection();
            List<Map<String, Object>> perSide = new ArrayList<>();
            List<Map<String, ColMeta>> metaBySide = new ArrayList<>();
            for (Side side : sides) {
                Map<String, ColMeta> meta = new LinkedHashMap<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT * FROM (" + side.relationSql() + ") AS __r LIMIT 0")) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int c = 1; c <= md.getColumnCount(); c++)
                        meta.put(md.getColumnLabel(c),
                                new ColMeta(md.getColumnLabel(c), md.getColumnTypeName(c), numeric(md.getColumnType(c))));
                }
                List<Map<String, Object>> cols = new ArrayList<>();
                for (ColMeta m : meta.values()) cols.add(m.wire());
                Map<String, Object> ds = new LinkedHashMap<>();
                ds.put("dataset", side.datasetId());
                ds.put("columns", cols);
                perSide.add(ds);
                metaBySide.add(meta);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("datasets", perSide);
            out.put("matches", matches(sides, metaBySide));
            return out;
        }
    }

    private record ColMeta(String name, String type, boolean numeric) {
        Map<String, Object> wire() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type);
            m.put("numeric", numeric);
            return m;
        }
    }

    /** Columns whose normalized name exists on every side → suggested unified bindings. */
    private static List<Map<String, Object>> matches(List<Side> sides, List<Map<String, ColMeta>> metaBySide) {
        List<Map<String, Object>> matches = new ArrayList<>();
        for (ColMeta first : metaBySide.get(0).values()) {
            String norm = normalize(first.name());
            Map<String, String> byDataset = new LinkedHashMap<>();
            byDataset.put(sides.get(0).datasetId(), first.name());
            boolean everywhere = true;
            boolean numeric = first.numeric();
            for (int i = 1; i < metaBySide.size(); i++) {
                ColMeta hit = null;
                for (ColMeta m : metaBySide.get(i).values())
                    if (normalize(m.name()).equals(norm)) { hit = m; break; }
                if (hit == null) { everywhere = false; break; }
                byDataset.put(sides.get(i).datasetId(), hit.name());
                numeric &= hit.numeric();
            }
            if (!everywhere) continue;
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("name", first.name());
            match.put("numeric", numeric);
            match.put("columns", byDataset);
            matches.add(match);
        }
        return matches;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean numeric(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.FLOAT, Types.REAL,
                 Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    // ── SQL builders (package-private for unit tests; all identifiers pre-validated) ──

    /** One side's grouped CTE body: unified keys aliased k0…, measures m0…, COUNT(*) as mr (presence marker). */
    static String sideSql(Spec spec, int side) {
        StringBuilder sb = new StringBuilder("SELECT ");
        List<String> keys = spec.keyColumns();
        for (int i = 0; i < keys.size(); i++)
            sb.append(q(spec.physical(side, keys.get(i)))).append(" AS ").append(q("k" + i)).append(", ");
        List<Measure> ms = spec.measures();
        for (int i = 0; i < ms.size(); i++) {
            Measure m = ms.get(i);
            sb.append("count".equals(m.agg()) ? "COUNT(" : "SUM(")
              .append(q(spec.physical(side, m.name()))).append(") AS ").append(q("m" + i)).append(", ");
        }
        sb.append("COUNT(*) AS ").append(q("mr"))
          .append(" FROM ").append(VIEWS[side]);
        String filter = spec.sides().get(side).filter();
        if (filter != null) sb.append(" WHERE (").append(ExpressionGuard.check(filter)).append(')');
        sb.append(" GROUP BY ");
        for (int i = 0; i < keys.size(); i++) sb.append(i > 0 ? ", " : "").append(i + 1);
        return sb.toString();
    }

    /** The Board grain query: FULL OUTER JOIN of the two grouped sides on NULL-safe key equality. */
    static String grainSql(Spec spec, int limit) {
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append("COALESCE(__s0.").append(q("k" + i)).append(", __s1.").append(q("k" + i)).append(") AS ")
              .append(q("k" + i)).append(", ");
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i < spec.measures().size(); i++)
                sb.append("__s").append(s).append('.').append(q("m" + i)).append(" AS ").append(q("s" + s + "_m" + i)).append(", ");
            sb.append("__s").append(s).append('.').append(q("mr")).append(" AS ").append(q("s" + s + "_mr"))
              .append(s == 0 ? ", " : "");
        }
        sb.append(" FROM __s0 FULL OUTER JOIN __s1 ON ").append(keyJoin(spec))
          .append(" ORDER BY ").append(orderByKeys(spec, "")).append(" LIMIT ").append(Math.max(0, limit) + 1);
        return sb.toString();
    }

    /** One side's exact totals (no GROUP BY — immune to grain truncation). */
    static String totalsSql(Spec spec, int side) {
        StringBuilder sb = new StringBuilder("SELECT ");
        List<Measure> ms = spec.measures();
        for (int i = 0; i < ms.size(); i++) {
            Measure m = ms.get(i);
            sb.append("count".equals(m.agg()) ? "COUNT(" : "SUM(")
              .append(q(spec.physical(side, m.name()))).append(") AS ").append(q("m" + i)).append(", ");
        }
        sb.append("COUNT(*) AS ").append(q("mr")).append(" FROM ").append(VIEWS[side]);
        String filter = spec.sides().get(side).filter();
        if (filter != null) sb.append(" WHERE (").append(ExpressionGuard.check(filter)).append(')');
        return sb.toString();
    }

    /** One-pass exact Break summary over the joined grain (value_break counts (key × column) entries, UI parity). */
    static String summarySql(Spec spec) {
        StringBuilder sb = new StringBuilder(with(spec))
                .append("SELECT COUNT(*) AS \"groups\", ")
                .append("COUNT(*) FILTER (WHERE __s0.\"mr\" IS NOT NULL AND __s1.\"mr\" IS NULL) AS \"missing_right\", ")
                .append("COUNT(*) FILTER (WHERE __s1.\"mr\" IS NOT NULL AND __s0.\"mr\" IS NULL) AS \"missing_left\", ")
                .append("COUNT(*) FILTER (WHERE __s0.\"mr\" IS NOT NULL AND __s1.\"mr\" IS NOT NULL) AS \"matched\", ");
        if (spec.measures().isEmpty()) {
            sb.append("0 AS \"value_break\"");
        } else {
            List<String> terms = new ArrayList<>();
            for (int i = 0; i < spec.measures().size(); i++)
                terms.add("COALESCE(SUM(CASE WHEN __s0.\"mr\" IS NOT NULL AND __s1.\"mr\" IS NOT NULL AND NOT "
                        + within("__s0." + q("m" + i), "__s1." + q("m" + i), spec.measures().get(i))
                        + " THEN 1 ELSE 0 END), 0)");
            sb.append('(').append(String.join(" + ", terms)).append(") AS \"value_break\"");
        }
        sb.append(" FROM __s0 FULL OUTER JOIN __s1 ON ").append(keyJoin(spec));
        return sb.toString();
    }

    /** Keys present on {@code side} and absent on the other ({@code side} 0 → missing_right, 1 → missing_left). */
    static String missingSql(Spec spec, int side, Map<String, String> path, int limit, int offset) {
        int other = 1 - side;
        String p = "__s" + side, o = "__s" + other;
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append(p).append('.').append(q("k" + i)).append(", ");
        for (int i = 0; i < spec.measures().size(); i++)
            sb.append(p).append('.').append(q("m" + i)).append(" AS ").append(q("s" + side + "_m" + i)).append(", ");
        sb.append(p).append('.').append(q("mr")).append(" AS ").append(q("s" + side + "_mr"))
          .append(" FROM ").append(p).append(" LEFT JOIN ").append(o).append(" ON ").append(keyJoin(spec))
          .append(" WHERE ").append(o).append(".\"mr\" IS NULL").append(pathPredicate(spec, path, p + "."))
          .append(" ORDER BY ").append(orderByKeys(spec, p + "."))
          .append(" LIMIT ").append(Math.max(0, limit) + 1).append(" OFFSET ").append(Math.max(0, offset));
        return sb.toString();
    }

    /** Matched keys where any compare column falls outside its tolerance. */
    static String valueBreaksSql(Spec spec, Map<String, String> path, int limit, int offset) {
        StringBuilder sb = new StringBuilder(with(spec)).append("SELECT ");
        for (int i = 0; i < spec.keyColumns().size(); i++)
            sb.append("__s0.").append(q("k" + i)).append(", ");
        for (int s = 0; s < 2; s++) {
            for (int i = 0; i < spec.measures().size(); i++)
                sb.append("__s").append(s).append('.').append(q("m" + i)).append(" AS ").append(q("s" + s + "_m" + i)).append(", ");
            sb.append("__s").append(s).append('.').append(q("mr")).append(" AS ").append(q("s" + s + "_mr"))
              .append(s == 0 ? ", " : "");
        }
        List<String> broken = new ArrayList<>();
        for (int i = 0; i < spec.measures().size(); i++)
            broken.add("NOT " + within("__s0." + q("m" + i), "__s1." + q("m" + i), spec.measures().get(i)));
        sb.append(" FROM __s0 JOIN __s1 ON ").append(keyJoin(spec))
          .append(" WHERE (").append(String.join(" OR ", broken)).append(')')
          .append(pathPredicate(spec, path, "__s0."))
          .append(" ORDER BY ").append(orderByKeys(spec, "__s0."))
          .append(" LIMIT ").append(Math.max(0, limit) + 1).append(" OFFSET ").append(Math.max(0, offset));
        return sb.toString();
    }

    /**
     * SQL port of the UI's {@code withinTolerance} over aggregated (numeric) values. NULL on one side only
     * is a mismatch; NULL on both matches. Percent is left(anchor)-relative; a zero anchor requires equality.
     */
    static String within(String a, String b, Measure m) {
        String tol = String.valueOf(m.tolerance());
        return switch (m.toleranceType()) {
            case "absolute" -> "(CASE WHEN " + a + " IS NULL OR " + b + " IS NULL THEN " + a + " IS NOT DISTINCT FROM " + b
                    + " ELSE ABS(" + a + " - " + b + ") <= " + tol + " END)";
            case "percent" -> "(CASE WHEN " + a + " IS NULL OR " + b + " IS NULL THEN " + a + " IS NOT DISTINCT FROM " + b
                    + " WHEN " + a + " = 0 THEN " + b + " = 0"
                    + " ELSE ABS(" + a + " - " + b + ") / ABS(" + a + ") * 100 <= " + tol + " END)";
            default -> "(" + a + " IS NOT DISTINCT FROM " + b + ")";
        };
    }

    private static String with(Spec spec) {
        return "WITH __s0 AS (" + sideSql(spec, 0) + "), __s1 AS (" + sideSql(spec, 1) + ") ";
    }

    /** NULL-safe key equality across the two grouped sides (NULL dim values match each other). */
    private static String keyJoin(Spec spec) {
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < spec.keyColumns().size(); i++)
            terms.add("__s0." + q("k" + i) + " IS NOT DISTINCT FROM __s1." + q("k" + i));
        return String.join(" AND ", terms);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static Connection registerSides(SqlSandbox sandbox, Spec spec) throws SQLException {
        Connection conn = sandbox.connection();
        for (int i = 0; i < 2; i++)
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE VIEW " + VIEWS[i] + " AS " + spec.sides().get(i).relationSql());
            }
        return conn;
    }

    private static BreakSet breakSet(Connection conn, Spec spec, String sql, int onlySide, int limit)
            throws SQLException {
        List<Map<String, Object>> raw = select(conn, sql);
        boolean truncated = raw.size() > limit;
        if (truncated) raw = raw.subList(0, limit);
        List<Map<String, Object>> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", keysOf(spec, r));
            if (onlySide != 1) row.put("a", measuresOf(spec, r, "s0_"));
            if (onlySide != 0) row.put("b", measuresOf(spec, r, "s1_"));
            rows.add(row);
        }
        return new BreakSet(rows, rows.size(), truncated);
    }

    private static Map<String, Object> shapeRow(Spec spec, Map<String, Object> r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", keysOf(spec, r));
        row.put("a", measuresOf(spec, r, "s0_"));
        row.put("b", measuresOf(spec, r, "s1_"));
        row.put("inA", r.get("s0_mr") != null);
        row.put("inB", r.get("s1_mr") != null);
        return row;
    }

    private static Map<String, Object> keysOf(Spec spec, Map<String, Object> r) {
        Map<String, Object> key = new LinkedHashMap<>();
        for (int i = 0; i < spec.keyColumns().size(); i++)
            key.put(spec.keyColumns().get(i), r.get("k" + i));
        return key;
    }

    /** Unified-named measure values from an internal-alias row; {@code prefix} null = totals row (no prefix). */
    private static Map<String, Object> measuresOf(Spec spec, Map<String, Object> r, String prefix) {
        String p = prefix == null ? "" : prefix;
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < spec.measures().size(); i++)
            m.put(spec.measures().get(i).name(), r.get(p + "m" + i));
        if (spec.includeRecordCount()) m.put(RECORDS, r.get(p + "mr"));
        return m;
    }

    private static List<Map<String, Object>> select(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), wire(rs.getObject(c)));
                rows.add(row);
            }
            return rows;
        }
    }

    /** ISO-8601-coerce temporals for the jsr310-free control-plane mapper (mirrors QueryExecutor). */
    private static Object wire(Object v) {
        return v instanceof java.time.temporal.Temporal ? v.toString() : v;
    }

    private static String pathPredicate(Spec spec, Map<String, String> path, String qualifier) {
        if (path == null || path.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : path.entrySet()) {
            int i = spec.keyColumns().indexOf(e.getKey());
            sb.append(" AND CAST(").append(qualifier).append(q("k" + i)).append(" AS VARCHAR) = ")
              .append(lit(e.getValue()));
        }
        return sb.toString();
    }

    /** Stable key ordering; {@code qualifier} is empty for output aliases or a CTE prefix like {@code "__s0."}. */
    private static String orderByKeys(Spec spec, String qualifier) {
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < spec.keyColumns().size(); i++)
            terms.add(qualifier + q("k" + i) + " NULLS LAST");
        return String.join(", ", terms);
    }

    private static void safeIdent(String s, String what) {
        if (s == null || !SAFE_IDENT.matcher(s).matches())
            throw new IllegalArgumentException(what + " must be a plain identifier, got '" + s + "'");
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
