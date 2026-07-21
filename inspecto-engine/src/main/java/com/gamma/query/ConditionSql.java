package com.gamma.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Renders the structured condition tree authored in the UI (the {@code query-types.ts} shape that
 * {@link ConditionTree} evaluates in-JVM) as a DuckDB SQL predicate, so the ETL engine can apply a
 * Decision Rule's {@code when} clause to a whole {@code transformed} table in one statement instead
 * of row-looping through the JVM.
 *
 * <p><b>Semantics parity.</b> The walk (AND/OR groups, incomplete leaves contribute nothing, an
 * empty/absent group imposes no constraint), the operator set, and the case-insensitive substring
 * ops all mirror {@link ConditionTree}. The one deliberate divergence is typing: {@code ConditionTree}
 * infers a column's type from sample values, while here the <em>operand literal</em> drives the cast —
 * a numeric operand compares via {@code TRY_CAST(col AS DOUBLE)} (a non-numeric cell casts to
 * {@code NULL} ⇒ no match, the SQL analogue of the evaluator's {@code NaN} path), an ISO date/time
 * operand via {@code TRY_CAST(col AS TIMESTAMP)}, {@code true/false} as booleans, anything else as a
 * case-sensitive {@code VARCHAR} comparison.
 *
 * <p>All identifiers and literals are quote-escaped here — the tree is authored config, and nothing
 * from it may reach the statement unescaped.
 */
public final class ConditionSql {

    private ConditionSql() {
    }

    /**
     * The tree as a DuckDB boolean expression. A {@code null}/non-group tree or an empty group
     * imposes no constraint and renders as {@code TRUE} (parity with {@link ConditionTree}'s
     * "empty group matches every row").
     */
    public static String predicate(Object when) {
        String g = group(when);
        return g == null ? "TRUE" : g;
    }

    // ── tree walk (mirrors ConditionTree.matchGroup) ─────────────────────────────

    /** Render a group, or {@code null} when it contributes no constraint. */
    private static String group(Object node) {
        if (!(node instanceof Map<?, ?> g)) return null;
        Object rawItems = g.get("items");
        if (rawItems == null) rawItems = g.get("conditions");
        List<?> items = rawItems instanceof List<?> l ? l : List.of();
        Object op = g.get("op");
        String joiner = "OR".equalsIgnoreCase(op == null ? "AND" : String.valueOf(op)) ? " OR " : " AND ";

        StringJoiner sql = new StringJoiner(joiner, "(", ")");
        int rendered = 0;
        for (Object it : items) {
            if (!(it instanceof Map<?, ?> m)) continue;
            String part = isGroup(m) ? group(m) : isComplete(m) ? condition(m) : null;
            if (part != null) {
                sql.add(part);
                rendered++;
            }
        }
        return rendered == 0 ? null : sql.toString();
    }

    private static boolean isGroup(Map<?, ?> m) {
        Object kind = m.get("kind");
        if ("group".equals(kind)) return true;
        if ("condition".equals(kind)) return false;
        return m.containsKey("items") || m.containsKey("conditions");
    }

    private static boolean isComplete(Map<?, ?> c) {
        String field = str(c.get("field"));
        String operator = str(c.get("operator"));
        if (field.isEmpty() || operator.isEmpty()) return false;
        if (operator.equals("isNull") || operator.equals("isNotNull")) return true;
        if (operator.equals("between")) return !str(c.get("value")).isEmpty() && !str(c.get("value2")).isEmpty();
        Object v = c.get("value");
        return v != null && !String.valueOf(v).isEmpty();
    }

    // ── one leaf ─────────────────────────────────────────────────────────────────

    private static String condition(Map<?, ?> c) {
        String f = ident(str(c.get("field")));
        String operator = str(c.get("operator"));
        String value = str(c.get("value"));
        String value2 = str(c.get("value2"));
        return switch (operator) {
            // ConditionTree treats null and '' alike for the null checks
            case "isNull" -> "(" + f + " IS NULL OR CAST(" + f + " AS VARCHAR) = '')";
            case "isNotNull" -> "(" + f + " IS NOT NULL AND CAST(" + f + " AS VARCHAR) <> '')";
            case "contains" -> like(f, value, true, true);
            case "startsWith" -> like(f, value, false, true);
            case "endsWith" -> like(f, value, true, false);
            case "in" -> in(f, value);
            case "between" -> "(" + typed(f, ">=", value) + " AND " + typed(f, "<=", value2) + ")";
            case "=", "!=", "<", "<=", ">", ">=" -> typed(f, operator, value);
            default -> "FALSE";
        };
    }

    private static String in(String f, String csv) {
        StringJoiner sql = new StringJoiner(" OR ", "(", ")");
        int n = 0;
        for (String x : csv.split(",")) {
            String xt = x.trim();
            if (!xt.isEmpty()) {
                sql.add(typed(f, "=", xt));
                n++;
            }
        }
        return n == 0 ? "FALSE" : sql.toString();
    }

    /** Case-insensitive substring match; {@code %}/{@code _}/{@code \} in the operand are escaped
     *  before the wildcard ends are added. */
    private static String like(String f, String value, boolean pre, boolean post) {
        String v = value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String p = (pre ? "%" : "") + v + (post ? "%" : "");
        return "LOWER(CAST(" + f + " AS VARCHAR)) LIKE " + lit(p) + " ESCAPE '\\'";
    }

    /** Operand-driven typed comparison (see class doc). */
    private static String typed(String f, String op, String v) {
        String sqlOp = "!=".equals(op) ? "<>" : op;
        if (isNumeric(v))
            return "TRY_CAST(" + f + " AS DOUBLE) " + sqlOp + " " + Double.parseDouble(v);
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            // ConditionTree: a cell is true when Boolean true or the string 'true'; false orders below true
            String cell = "(CASE WHEN LOWER(CAST(" + f + " AS VARCHAR)) = 'true' THEN 1 ELSE 0 END)";
            return cell + " " + sqlOp + " " + ("true".equalsIgnoreCase(v) ? 1 : 0);
        }
        if (isDateLike(v))
            return "TRY_CAST(CAST(" + f + " AS VARCHAR) AS TIMESTAMP) " + sqlOp + " TRY_CAST(" + lit(v) + " AS TIMESTAMP)";
        return "CAST(" + f + " AS VARCHAR) " + sqlOp + " " + lit(v);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Same shape-guarded date sniff as {@link ConditionTree}: 4-digit year + a date/time separator. */
    private static boolean isDateLike(String s) {
        if (s == null || !s.matches(".*\\d{4}.*") || !s.matches(".*[-/:T].*")) return false;
        String v = s.trim();
        try {
            Instant.parse(v);
            return true;
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            LocalDateTime.parse(v);
            return true;
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            LocalDate.parse(v);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ── quoting ──────────────────────────────────────────────────────────────────

    private static String ident(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static String lit(String v) {
        return "'" + v.replace("'", "''") + "'";
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
