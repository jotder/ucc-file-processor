package com.gamma.query;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the R3 <b>Result Set</b> descriptor (W4; design §6.2) — the Java mirror of the UI's
 * {@code inspecto/viz/result-set.ts}: each output column described as {name, type, analytic role,
 * cardinality}, independent of how it is rendered, so the Presentation Network can match candidate
 * visualizations against the shape (Show-Me). Types come from JDBC {@link Types}; roles + cardinality
 * are inferred exactly as the UI does (date⇒temporal, non-id number⇒measure, else dimension).
 */
public final class ResultSetDescriptor {

    private ResultSetDescriptor() {}

    /** One described column: name, coarse type ({@code number|string|date|boolean}), role, and (dimensions) cardinality. */
    public record Column(String name, String type, String role, Integer cardinality) {}

    private static final Pattern ID_COLUMN = Pattern.compile("(^|_)id$", Pattern.CASE_INSENSITIVE);

    /** Map a JDBC {@link Types} constant to the UI's coarse {@code ColumnType}. */
    public static String columnType(int sqlType) {
        return switch (sqlType) {
            case Types.INTEGER, Types.BIGINT, Types.SMALLINT, Types.TINYINT,
                 Types.DECIMAL, Types.NUMERIC, Types.DOUBLE, Types.FLOAT, Types.REAL -> "number";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.DATE, Types.TIMESTAMP, Types.TIME,
                 Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> "date";
            default -> "string";
        };
    }

    /** Describe columns from their JDBC types + the returned rows (cardinality = distinct values, dimensions only). */
    public static List<Column> describe(List<String> names, List<Integer> sqlTypes, List<Map<String, Object>> rows) {
        List<Column> cols = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String type = columnType(sqlTypes.get(i));
            String role = roleFor(name, type);
            Integer cardinality = "dimension".equals(role) ? distinctCount(rows, name) : null;
            cols.add(new Column(name, type, role, cardinality));
        }
        return cols;
    }

    static String roleFor(String name, String type) {
        if ("date".equals(type)) return "temporal";
        if ("number".equals(type) && !ID_COLUMN.matcher(name).find()) return "measure";
        return "dimension";
    }

    private static int distinctCount(List<Map<String, Object>> rows, String col) {
        Set<Object> seen = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) seen.add(r.get(col));
        return seen.size();
    }

    /**
     * A coarse server-side candidate list of renderings from the column roles (design §6.2 / guideline 21).
     * The UI's richer {@code recommend()} refines this; here we give a stable, honest first cut.
     */
    public static List<String> renderings(List<Column> cols) {
        long measures = cols.stream().filter(c -> "measure".equals(c.role())).count();
        long dims = cols.stream().filter(c -> "dimension".equals(c.role())).count();
        long temporal = cols.stream().filter(c -> "temporal".equals(c.role())).count();
        List<String> out = new ArrayList<>();
        out.add("table");                                       // always applicable
        if (measures == 1 && dims == 0 && temporal == 0) out.add("kpi");
        if (temporal >= 1 && measures >= 1) out.add("line-chart");
        if (dims >= 1 && measures >= 1) out.add("bar-chart");
        if (dims >= 2 && measures >= 1) out.add("heatmap");
        return out;
    }
}
