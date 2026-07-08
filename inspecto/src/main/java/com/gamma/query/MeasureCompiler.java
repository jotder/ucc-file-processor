package com.gamma.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compiles a headless BI query spec (BI-7) — dataset + measures + dimensions + filters — into one guarded
 * DuckDB SELECT. The <b>server-side twin</b> of {@code inspecto-ui/.../viz/query-spec.ts#compileSpec} (that
 * file's declared "swap seam between offline AlaSQL and a backend DuckDB endpoint"): same aggregation set,
 * same {@code agg_field} measure ids, same filter semantics, so a widget's QuerySpec round-trips 1:1.
 *
 * <p>Everything is built from <b>validated identifiers and typed literals</b> — no caller SQL text ever
 * enters the statement — and the compiled text is still {@code SqlGuard}-checked by the route (defence in
 * depth). Unknown aggregation, operator, or a non-identifier field → {@link IllegalArgumentException}
 * (→ 422 at the route).
 */
public final class MeasureCompiler {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> AGGS = List.of("count", "countDistinct", "sum", "avg", "min", "max");

    private MeasureCompiler() {}

    /** One aggregation over a column ({@code count} ignores the field; all others require one). */
    public record Measure(String agg, String field) {
        /** The stable result-column id ({@code sum_AMT}, {@code count}) — matches the UI's measureId(). */
        public String id() {
            return "count".equals(agg) ? "count" : (agg + "_" + field).replaceAll("[^A-Za-z0-9_]", "_");
        }
    }

    /** One filter term; {@code op} ∈ = != > >= < <= in like isNull notNull. */
    public record Filter(String field, String op, Object value) {}

    /** One ORDER BY term. */
    public record Sort(String field, boolean descending) {}

    /** The parsed spec: at least one measure or one dimension; {@code dataset} is resolved by the route. */
    public record Spec(String dataset, List<Measure> measures, List<String> groupBy,
                       List<Filter> filters, List<Sort> orderBy, int limit) {}

    /** Parse the {@code POST /bi/query} body into a validated {@link Spec}. */
    public static Spec parse(Map<String, Object> body, int defaultLimit, int maxLimit) {
        String dataset = str(body.get("dataset"));
        if (dataset == null) throw new IllegalArgumentException("body must include 'dataset'");

        List<Measure> measures = new ArrayList<>();
        if (body.get("measures") instanceof List<?> ms)
            for (Object o : ms)
                if (o instanceof Map<?, ?> m) {
                    String agg = str(m.get("agg"));
                    String field = str(m.get("field"));
                    if (agg == null || !AGGS.contains(agg))
                        throw new IllegalArgumentException("unknown aggregation '" + agg + "' (one of " + AGGS + ")");
                    if (!"count".equals(agg)) safeIdent(field, "measure field");
                    measures.add(new Measure(agg, field));
                }

        List<String> groupBy = new ArrayList<>();
        if (body.get("groupBy") instanceof List<?> gs)
            for (Object g : gs) groupBy.add(safeIdent(str(g), "groupBy column"));

        if (measures.isEmpty() && groupBy.isEmpty())
            throw new IllegalArgumentException("spec needs at least one measure or groupBy column");

        List<Filter> filters = new ArrayList<>();
        if (body.get("filters") instanceof List<?> fs)
            for (Object o : fs)
                if (o instanceof Map<?, ?> f)
                    filters.add(new Filter(safeIdent(str(f.get("field")), "filter field"),
                            str(f.get("op")), f.get("value")));

        List<Sort> orderBy = new ArrayList<>();
        if (body.get("orderBy") instanceof List<?> os)
            for (Object o : os)
                if (o instanceof Map<?, ?> s)
                    orderBy.add(new Sort(safeIdent(str(s.get("field")), "orderBy field"),
                            "desc".equalsIgnoreCase(str(s.get("dir")))));

        int limit = body.get("limit") instanceof Number n ? n.intValue() : defaultLimit;
        return new Spec(dataset, measures, groupBy, filters, orderBy,
                Math.max(1, Math.min(maxLimit, limit)));
    }

    /** Compile the spec to the guarded SELECT (the dataset is referenced by its registered view name). */
    public static String compile(Spec spec) {
        List<String> select = new ArrayList<>();
        for (String dim : spec.groupBy()) select.add(q(dim));
        for (Measure m : spec.measures()) select.add(aggExpression(m) + " AS " + q(m.id()));

        StringBuilder sql = new StringBuilder("SELECT ")
                .append(select.isEmpty() ? "*" : String.join(", ", select))
                .append(" FROM ").append(q(spec.dataset()));

        if (!spec.filters().isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (Filter f : spec.filters()) terms.add(filterTerm(f));
            sql.append(" WHERE ").append(String.join(" AND ", terms));
        }
        if (!spec.measures().isEmpty() && !spec.groupBy().isEmpty())
            sql.append(" GROUP BY ").append(String.join(", ", spec.groupBy().stream().map(MeasureCompiler::q).toList()));
        if (!spec.orderBy().isEmpty()) {
            List<String> terms = new ArrayList<>();
            for (Sort s : spec.orderBy()) terms.add(q(s.field()) + (s.descending() ? " DESC" : " ASC"));
            sql.append(" ORDER BY ").append(String.join(", ", terms));
        }
        sql.append(" LIMIT ").append(spec.limit());
        return sql.toString();
    }

    /** The SQL aggregate for a measure — the same set as the UI's aggExpression(). */
    private static String aggExpression(Measure m) {
        return switch (m.agg()) {
            case "count" -> "COUNT(*)";
            case "countDistinct" -> "COUNT(DISTINCT " + q(m.field()) + ")";
            case "sum", "avg", "min", "max" -> m.agg().toUpperCase() + "(" + q(m.field()) + ")";
            default -> throw new IllegalArgumentException("unknown aggregation '" + m.agg() + "'");
        };
    }

    private static String filterTerm(Filter f) {
        String col = q(f.field());
        String op = f.op() == null ? "=" : f.op().trim();
        return switch (op) {
            case "=", "==", "eq" -> col + " = " + literal(f.value());
            case "!=", "<>", "ne" -> col + " <> " + literal(f.value());
            case ">", "gt" -> col + " > " + literal(f.value());
            case ">=", "gte" -> col + " >= " + literal(f.value());
            case "<", "lt" -> col + " < " + literal(f.value());
            case "<=", "lte" -> col + " <= " + literal(f.value());
            case "like" -> col + " LIKE " + literal(str(f.value()));
            case "isNull" -> col + " IS NULL";
            case "notNull" -> col + " IS NOT NULL";
            case "in" -> {
                if (!(f.value() instanceof List<?> vs) || vs.isEmpty())
                    throw new IllegalArgumentException("'in' filter needs a non-empty value list");
                List<String> lits = new ArrayList<>();
                for (Object v : vs) lits.add(literal(v));
                yield col + " IN (" + String.join(", ", lits) + ")";
            }
            default -> throw new IllegalArgumentException("unknown filter op '" + op + "'");
        };
    }

    /** A typed SQL literal: numbers and booleans verbatim, everything else a single-quoted string. */
    private static String literal(Object v) {
        if (v == null) throw new IllegalArgumentException("filter value must not be null (use isNull/notNull)");
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        return "'" + v.toString().replace("'", "''") + "'";
    }

    private static String safeIdent(String s, String what) {
        if (s == null || !SAFE_IDENT.matcher(s).matches())
            throw new IllegalArgumentException("unsafe " + what + " '" + s + "'");
        return s;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
