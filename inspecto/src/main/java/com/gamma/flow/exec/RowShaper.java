package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.flow.BuiltinNodeType;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>T10 — row-shaping SQL assembly.</b> Executes one flow {@code transform.*} node as SQL over a DuckDB
 * input relation, producing one or more <b>named output relations</b> (the multi-named-relation node-output
 * contract T9 made enforceable). Each output relation is materialised as a DuckDB table named
 * {@code <outPrefix>__<relkey>} and returned as a {@link Relation} ({@code rel} = the {@link FlowRel}
 * the edge carries, {@code table} = the DuckDB table).
 *
 * <p>This is the thing the legacy {@link com.gamma.etl.DataTransformer} could not do: it emitted exactly
 * one {@code SELECT … FROM <one source>} into one table (column-scalar only). Here each operator can add
 * a {@code WHERE} (filter / validate), a {@code CASE}/per-branch predicate (route), {@code QUALIFY}
 * (dedup), {@code UNNEST} (split) or a multi-input join/union (merge), and split a batch across several
 * named relations. Per-column expressions still reuse {@link com.gamma.etl.TransformCompiler}'s trust model
 * (author-owned scalar SQL emitted verbatim).
 *
 * <h3>Authored config contracts (node {@code config})</h3>
 * <ul>
 *   <li>{@code transform.filter} — {@code where}: bool SQL → {@code data} (kept) + {@code dropped}.</li>
 *   <li>{@code transform.validate} — {@code rule}: bool SQL → {@code data} (valid) + {@code invalid}.</li>
 *   <li>{@code transform.route} — {@code mode}: {@code case}|{@code clone} (default {@code case});
 *       {@code branches}: [{@code {key, where}}]; optional {@code default} key → one {@code route:<key>}
 *       relation per branch.</li>
 *   <li>{@code transform.dedup[.*]} — {@code keys}: [col]; optional {@code order_by} (SQL) →
 *       {@code data} (first per key) + {@code duplicate}.</li>
 *   <li>{@code transform.split} — {@code column}: list/array col; optional {@code as} → {@code data}.</li>
 *   <li>{@code transform.map}/{@code select}/{@code derive} — {@code columns}: map/select take
 *       [{@code {name, expr}}] / [name]; derive adds [{@code {name, expr}}] to the input columns →
 *       {@code data}.</li>
 *   <li>{@code transform.merge} — {@code type}: {@code union}|{@code inner}|{@code left} (default
 *       {@code union}); {@code on}: [col] for joins → {@code data} (see {@link #merge}).</li>
 * </ul>
 *
 * <p>NULL-safe partitioning: a predicate that evaluates to {@code NULL} sends the row to the negative
 * side ({@code dropped}/{@code invalid}) — i.e. {@code data} keeps {@code COALESCE(pred, FALSE)} only.
 */
@PublicApi(since = "4.3.0")
public final class RowShaper {

    private RowShaper() {}

    /** A produced relation: the {@link FlowRel} an outbound edge carries + the DuckDB table holding it. */
    public record Relation(String rel, String table) {}

    /**
     * Shape a single-input {@code transform.*} node over {@code input}, creating its output tables under
     * {@code outPrefix}. {@code transform.merge} is multi-input — call {@link #merge} instead.
     */
    public static List<Relation> shape(Connection conn, FlowNode node, String input, String outPrefix)
            throws SQLException {
        String type = node.type();
        if (BuiltinNodeType.TRANSFORM_FILTER.type().equals(type))   return filter(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_VALIDATE.type().equals(type)) return validate(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_ROUTE.type().equals(type))    return route(conn, node, input, outPrefix);
        if (type.startsWith("transform.dedup"))                      return dedup(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_SPLIT.type().equals(type))    return split(conn, node, input, outPrefix);
        if (BuiltinNodeType.TRANSFORM_MAP.type().equals(type)
                || BuiltinNodeType.TRANSFORM_SELECT.type().equals(type)
                || BuiltinNodeType.TRANSFORM_DERIVE.type().equals(type)) return project(conn, node, input, outPrefix);
        throw new IllegalArgumentException("RowShaper cannot shape node type '" + type + "' (id=" + node.id() + ")");
    }

    // ── filter / validate (predicate split) ────────────────────────────────────

    private static List<Relation> filter(Connection conn, FlowNode node, String input, String p) throws SQLException {
        return predicateSplit(conn, input, p, str(node, "where"), FlowRel.DATA, FlowRel.DROPPED);
    }

    private static List<Relation> validate(Connection conn, FlowNode node, String input, String p) throws SQLException {
        return predicateSplit(conn, input, p, str(node, "rule"), FlowRel.DATA, FlowRel.INVALID);
    }

    /** Split {@code input} on a boolean {@code pred}: keep-side gets {@code COALESCE(pred,FALSE)}, the rest go negative. */
    private static List<Relation> predicateSplit(Connection conn, String input, String prefix,
                                                 String pred, String keepRel, String dropRel) throws SQLException {
        requireExpr(pred, "predicate");
        String keep = table(prefix, keepRel);
        String drop = table(prefix, dropRel);
        exec(conn, "CREATE TABLE " + q(keep) + " AS SELECT * FROM " + q(input) + " WHERE COALESCE((" + pred + "), FALSE)");
        exec(conn, "CREATE TABLE " + q(drop) + " AS SELECT * FROM " + q(input) + " WHERE NOT COALESCE((" + pred + "), FALSE)");
        return List.of(new Relation(keepRel, keep), new Relation(dropRel, drop));
    }

    // ── route (content-based branching) ─────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Relation> route(Connection conn, FlowNode node, String input, String prefix) throws SQLException {
        Object raw = node.cfg("branches");
        if (!(raw instanceof List<?> branchList) || branchList.isEmpty())
            throw new IllegalArgumentException("transform.route node '" + node.id() + "' needs a non-empty 'branches' list");
        boolean clone = "clone".equalsIgnoreCase(str(node, "mode"));   // default = case (exclusive)
        String defaultKey = strOrNull(node, "default");

        List<Map<String, Object>> branches = new ArrayList<>();
        for (Object b : branchList) branches.add((Map<String, Object>) b);

        List<Relation> out = new ArrayList<>();
        if (clone) {
            // independent: a row may leave on several branches
            for (Map<String, Object> b : branches) {
                String key = reqStr(b, "key", node.id());
                String where = reqStr(b, "where", node.id());
                String tbl = table(prefix, FlowRel.route(key));
                exec(conn, "CREATE TABLE " + q(tbl) + " AS SELECT * FROM " + q(input)
                        + " WHERE COALESCE((" + where + "), FALSE)");
                out.add(new Relation(FlowRel.route(key), tbl));
            }
            return out;
        }
        // case (exclusive, first-match-wins): label each row, then split by label
        StringBuilder cse = new StringBuilder("CASE");
        for (Map<String, Object> b : branches) {
            String key = reqStr(b, "key", node.id());
            String where = reqStr(b, "where", node.id());
            cse.append(" WHEN COALESCE((").append(where).append("), FALSE) THEN ").append(sqlStr(key));
        }
        cse.append(" ELSE ").append(defaultKey == null ? "NULL" : sqlStr(defaultKey)).append(" END");
        String labelled = table(prefix, "labelled");
        exec(conn, "CREATE TABLE " + q(labelled) + " AS SELECT *, (" + cse + ") AS __route FROM " + q(input));

        List<String> emitted = new ArrayList<>();
        for (Map<String, Object> b : branches) emitted.add(reqStr(b, "key", node.id()));
        if (defaultKey != null && !emitted.contains(defaultKey)) emitted.add(defaultKey);
        for (String key : emitted) {
            String tbl = table(prefix, FlowRel.route(key));
            exec(conn, "CREATE TABLE " + q(tbl) + " AS SELECT * EXCLUDE(__route) FROM " + q(labelled)
                    + " WHERE __route = " + sqlStr(key));
            out.add(new Relation(FlowRel.route(key), tbl));
        }
        return out;
    }

    // ── dedup (QUALIFY) ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Relation> dedup(Connection conn, FlowNode node, String input, String prefix) throws SQLException {
        Object keysRaw = node.cfg("keys");
        if (!(keysRaw instanceof List<?> keyList) || keyList.isEmpty())
            throw new IllegalArgumentException("transform.dedup node '" + node.id() + "' needs a non-empty 'keys' list");
        String partition = String.join(", ", ((List<Object>) keysRaw).stream().map(k -> q(k.toString())).toList());
        String order = strOrNull(node, "order_by");
        String window = "ROW_NUMBER() OVER (PARTITION BY " + partition
                + (order == null ? "" : " ORDER BY " + order) + ")";
        String data = table(prefix, FlowRel.DATA);
        String dup  = table(prefix, FlowRel.DUPLICATE);
        // QUALIFY needs the window in the predicate; compute rn once in a subquery so both sides agree.
        String ranked = "(SELECT *, " + window + " AS __rn FROM " + q(input) + ")";
        exec(conn, "CREATE TABLE " + q(data) + " AS SELECT * EXCLUDE(__rn) FROM " + ranked + " WHERE __rn = 1");
        exec(conn, "CREATE TABLE " + q(dup)  + " AS SELECT * EXCLUDE(__rn) FROM " + ranked + " WHERE __rn > 1");
        return List.of(new Relation(FlowRel.DATA, data), new Relation(FlowRel.DUPLICATE, dup));
    }

    // ── split (UNNEST) ────────────────────────────────────────────────────────────

    private static List<Relation> split(Connection conn, FlowNode node, String input, String prefix) throws SQLException {
        String col = str(node, "column");
        requireExpr(col, "column");
        String as = strOrNull(node, "as");
        if (as == null) as = col;
        String data = table(prefix, FlowRel.DATA);
        exec(conn, "CREATE TABLE " + q(data) + " AS SELECT * EXCLUDE(" + q(col) + "), UNNEST(" + q(col)
                + ") AS " + q(as) + " FROM " + q(input));
        return List.of(new Relation(FlowRel.DATA, data));
    }

    // ── projection (map / select / derive) ─────────────────────────────────────────

    private static List<Relation> project(Connection conn, FlowNode node, String input, String prefix) throws SQLException {
        String data = table(prefix, FlowRel.DATA);
        exec(conn, "CREATE TABLE " + q(data) + " AS " + projectionSelect(node, input));
        return List.of(new Relation(FlowRel.DATA, data));
    }

    /** The {@code SELECT … FROM <input>} for a projection node (reused by {@link #fuse}). */
    @SuppressWarnings("unchecked")
    private static String projectionSelect(FlowNode node, String input) {
        String type = node.type();
        Object colsRaw = node.cfg("columns");
        if (!(colsRaw instanceof List<?> cols) || cols.isEmpty())
            throw new IllegalArgumentException(type + " node '" + node.id() + "' needs a non-empty 'columns' list");
        StringBuilder sel = new StringBuilder("SELECT ");
        if (BuiltinNodeType.TRANSFORM_SELECT.type().equals(type)) {           // narrow to named columns
            List<String> names = new ArrayList<>();
            for (Object c : cols) names.add(q(c.toString()));
            sel.append(String.join(", ", names));
        } else {
            boolean derive = BuiltinNodeType.TRANSFORM_DERIVE.type().equals(type);
            if (derive) sel.append("*, ");                                    // derive keeps input columns
            List<String> exprs = new ArrayList<>();
            for (Object c : cols) {
                Map<String, Object> m = (Map<String, Object>) c;
                String name = reqStr(m, "name", node.id());
                String expr = reqStr(m, "expr", node.id());
                exprs.add("(" + expr + ") AS " + q(name));
            }
            sel.append(String.join(", ", exprs));
        }
        return sel.append(" FROM ").append(q(input)).toString();
    }

    /**
     * <b>Chain-fusion (T10).</b> Fuse a linear run of projection ({@code map}/{@code select}/{@code derive})
     * and {@code filter} nodes into a <b>single</b> {@code SELECT … WHERE …} pass over {@code input},
     * avoiding an intermediate table per node. Safe for the common shape (a projection plus filters whose
     * predicates and expressions reference the chain's <em>input</em> columns); the executor falls back to
     * per-node {@link #shape} for anything that interdepends. Emits one {@code data} relation.
     */
    public static Relation fuse(Connection conn, List<FlowNode> chain, String input, String outPrefix)
            throws SQLException {
        if (chain.isEmpty()) throw new IllegalArgumentException("fuse needs at least one node");
        String projection = "SELECT * FROM " + q(input);
        List<String> wheres = new ArrayList<>();
        for (FlowNode n : chain) {
            if (BuiltinNodeType.TRANSFORM_FILTER.type().equals(n.type())) {
                String w = str(n, "where");
                requireExpr(w, "predicate");
                wheres.add("COALESCE((" + w + "), FALSE)");
            } else {
                projection = projectionSelect(n, input);   // last projection wins (input-referencing)
            }
        }
        String data = table(outPrefix, FlowRel.DATA);
        String sql = "CREATE TABLE " + q(data) + " AS " + projection;
        if (!wheres.isEmpty()) sql += (projection.toUpperCase().contains(" WHERE ") ? " AND " : " WHERE ")
                + String.join(" AND ", wheres);
        exec(conn, sql);
        return new Relation(FlowRel.DATA, data);
    }

    // ── merge (multi-input join / union) ───────────────────────────────────────────

    /**
     * Merge several input relations into one {@code data} relation. {@code type=union} → {@code UNION ALL BY
     * NAME} (column-name aligned); {@code type=inner|left} → an {@code N}-way join on the {@code on} columns
     * (a node with fan-in {@code data} edges).
     */
    @SuppressWarnings("unchecked")
    public static List<Relation> merge(Connection conn, FlowNode node, List<String> inputs, String outPrefix)
            throws SQLException {
        if (inputs.size() < 2) throw new IllegalArgumentException("transform.merge node '" + node.id()
                + "' needs >= 2 inputs, got " + inputs.size());
        String type = strOrNull(node, "type");
        String data = table(outPrefix, FlowRel.DATA);
        String sql;
        if (type == null || "union".equalsIgnoreCase(type)) {
            List<String> parts = new ArrayList<>();
            for (String in : inputs) parts.add("SELECT * FROM " + q(in));
            sql = String.join(" UNION ALL BY NAME ", parts);
        } else {
            Object onRaw = node.cfg("on");
            if (!(onRaw instanceof List<?> onList) || onList.isEmpty())
                throw new IllegalArgumentException("transform.merge join on node '" + node.id() + "' needs an 'on' column list");
            String using = "USING (" + String.join(", ", ((List<Object>) onRaw).stream().map(o -> q(o.toString())).toList()) + ")";
            String join = "inner".equalsIgnoreCase(type) ? " JOIN " : " LEFT JOIN ";
            StringBuilder from = new StringBuilder(q(inputs.get(0)));
            for (int i = 1; i < inputs.size(); i++) from.append(join).append(q(inputs.get(i))).append(' ').append(using);
            sql = "SELECT * FROM " + from;
        }
        exec(conn, "CREATE TABLE " + q(data) + " AS " + sql);
        return List.of(new Relation(FlowRel.DATA, data));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    /** A safe, unique DuckDB table identifier for {@code <prefix>__<relkey>}. */
    private static String table(String prefix, String rel) {
        return sanitize(prefix) + "__" + sanitize(rel);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** Double-quote an identifier (escaping embedded quotes). */
    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** A single-quoted SQL string literal. */
    private static String sqlStr(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String str(FlowNode n, String key) {
        Object v = n.cfg(key);
        return v == null ? null : v.toString();
    }

    private static String strOrNull(FlowNode n, String key) {
        String v = str(n, key);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static void requireExpr(String v, String what) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("missing " + what);
    }

    private static String reqStr(Map<String, Object> m, String key, String nodeId) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("node '" + nodeId + "': missing '" + key + "'");
        return v.toString();
    }
}
