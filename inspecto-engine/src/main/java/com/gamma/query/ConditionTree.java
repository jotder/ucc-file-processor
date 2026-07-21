package com.gamma.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, dependency-free evaluator for the structured condition tree authored in the UI — a faithful
 * Java port of the browser-side offline query engine ({@code inspecto-ui/src/app/inspecto/query/
 * query-eval.ts}) plus its type inference ({@code query-columns.ts}). It exists so the backend can
 * count row matches with <b>exactly</b> the semantics the authoring UI previews — the Decision Rule
 * {@code simulate} route ({@link com.gamma.control} {@code /decision-rules/{name}/simulate}) is the
 * first consumer, evaluating a rule's {@code when} tree over a caller-supplied {@code sampleRows}
 * batch.
 *
 * <p><b>Tree shape</b> (the {@code query-types.ts} model): a group is
 * {@code {kind:'group', op:'AND'|'OR', items:[…]}}; a leaf is
 * {@code {kind:'condition', field, operator, value?, value2?}}. Operators, comparison rules, the
 * case-insensitive substring ops, {@code in}/{@code between}, and the "empty group ⇒ no constraint"
 * rule all mirror the TS reference so a rule counted here matches the same rows it would in-browser.
 * Column types are inferred from the sample rows the same way {@code inferColumns()} does when no
 * metadata is supplied.
 *
 * <p>No filesystem, no SQL, no config surface — it only reads the caller's in-memory rows, so it
 * needs no {@code ConfigSafetyValidator} pass; the sample is bounded by the HTTP request body.
 */
public final class ConditionTree {

    private ConditionTree() {
    }

    private enum ColType { NUMBER, STRING, DATE, BOOLEAN }

    /**
     * Count how many of {@code rows} satisfy the {@code when} tree. A {@code null} / non-group
     * {@code when}, or an empty group, imposes no constraint and matches every row (mirrors
     * {@code matchGroup}'s "no constraint ⇒ true"). Column types are inferred from {@code rows}.
     */
    public static int matched(Object when, List<Map<String, Object>> rows) {
        return filter(when, rows).size();
    }

    /**
     * The subset of {@code rows} satisfying the {@code when} tree, in their original order — the
     * row-level analogue of {@link #matched}, used where the matching rows themselves are needed
     * (e.g. an Alert Rule's {@code when} pre-filter over ledger rows) rather than just a count.
     */
    public static List<Map<String, Object>> filter(Object when, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        Map<String, ColType> types = inferColumns(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) if (matchGroup(when, row, types)) out.add(row);
        return out;
    }

    // ── tree walk (port of matchGroup / matchCondition / isComplete) ────────────────

    @SuppressWarnings("unchecked")
    private static boolean matchGroup(Object node, Map<String, Object> row, Map<String, ColType> types) {
        if (!(node instanceof Map<?, ?> g)) return true; // absent/garbage tree ⇒ no constraint
        Object rawItems = g.get("items");
        if (rawItems == null) rawItems = g.get("conditions");
        List<?> items = rawItems instanceof List<?> l ? l : List.of();
        Object op = g.get("op");
        boolean or = "OR".equalsIgnoreCase(op == null ? "AND" : String.valueOf(op));
        boolean any = false;
        boolean all = true;
        int evaluated = 0;
        for (Object it : items) {
            if (!(it instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            boolean res;
            if (isGroup(item)) {
                res = matchGroup(item, row, types);
            } else if (isComplete(item)) {
                res = matchCondition(item, row, types);
            } else {
                continue; // still-being-built leaf contributes nothing (parity with the UI preview)
            }
            evaluated++;
            any |= res;
            all &= res;
        }
        if (evaluated == 0) return true; // empty / incomplete group ⇒ no constraint
        return or ? any : all;
    }

    /** A node is a group when it declares {@code kind:'group'}, or (kind absent) it carries a nested
     *  item list rather than a leaf's {@code field}/{@code operator}. */
    private static boolean isGroup(Map<String, Object> m) {
        Object kind = m.get("kind");
        if ("group".equals(kind)) return true;
        if ("condition".equals(kind)) return false;
        return m.containsKey("items") || m.containsKey("conditions");
    }

    /** A leaf contributes to the predicate only once it has enough input to evaluate (port of
     *  {@code isComplete}). */
    private static boolean isComplete(Map<String, Object> c) {
        String field = str(c.get("field"));
        String operator = str(c.get("operator"));
        if (field.isEmpty() || operator.isEmpty()) return false;
        if (operator.equals("isNull") || operator.equals("isNotNull")) return true;
        if (operator.equals("between")) return !str(c.get("value")).isEmpty() && !str(c.get("value2")).isEmpty();
        Object v = c.get("value");
        return v != null && !String.valueOf(v).isEmpty();
    }

    private static boolean matchCondition(Map<String, Object> c, Map<String, Object> row, Map<String, ColType> types) {
        String field = str(c.get("field"));
        String operator = str(c.get("operator"));
        Object raw = row.get(field);
        ColType t = types.getOrDefault(field, ColType.STRING);

        if (operator.equals("isNull")) return raw == null || "".equals(raw);
        if (operator.equals("isNotNull")) return raw != null && !"".equals(raw);
        if (raw == null) return false;

        String s = String.valueOf(raw).toLowerCase(Locale.ROOT);
        String value = str(c.get("value"));
        String value2 = str(c.get("value2"));
        return switch (operator) {
            case "contains" -> s.contains(value.toLowerCase(Locale.ROOT));
            case "startsWith" -> s.startsWith(value.toLowerCase(Locale.ROOT));
            case "endsWith" -> s.endsWith(value.toLowerCase(Locale.ROOT));
            case "in" -> {
                for (String x : value.split(",")) {
                    String xt = x.trim();
                    if (!xt.isEmpty() && cmp(raw, xt, t) == 0) yield true;
                }
                yield false;
            }
            case "between" -> cmp(raw, value, t) >= 0 && cmp(raw, value2, t) <= 0;
            case "=" -> cmp(raw, value, t) == 0;
            case "!=" -> cmp(raw, value, t) != 0;
            case "<" -> cmp(raw, value, t) < 0;
            case "<=" -> cmp(raw, value, t) <= 0;
            case ">" -> cmp(raw, value, t) > 0;
            case ">=" -> cmp(raw, value, t) >= 0;
            default -> false;
        };
    }

    // ── typed comparison (port of cmp) ──────────────────────────────────────────────

    /** Compare a row value with a typed string operand; returns &lt;0, 0 or &gt;0 (port of {@code cmp}). */
    private static int cmp(Object raw, String operand, ColType type) {
        if (type == ColType.NUMBER) {
            double a = toNumber(raw), b = toNumber(operand);
            return a == b ? 0 : a < b ? -1 : 1; // NaN falls through to 1, matching JS
        }
        if (type == ColType.DATE) {
            Long a = toEpoch(String.valueOf(raw)), b = toEpoch(operand);
            if (a != null && b != null) return a.equals(b) ? 0 : a < b ? -1 : 1;
            // both-not-parseable ⇒ fall through to string compare (matches the TS guard)
        }
        if (type == ColType.BOOLEAN) {
            boolean a = raw instanceof Boolean bo ? bo : "true".equalsIgnoreCase(String.valueOf(raw));
            boolean b = "true".equalsIgnoreCase(operand);
            return a == b ? 0 : a ? 1 : -1;
        }
        String a = String.valueOf(raw);
        return a.equals(operand) ? 0 : a.compareTo(operand) < 0 ? -1 : 1;
    }

    /** Mimic JS {@code Number()}: numeric stays numeric, blank ⇒ 0, otherwise parse or {@code NaN}. */
    private static double toNumber(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return 0d;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Best-effort {@code Date.parse} equivalent: ISO instant/date-time/date; {@code null} if unparseable. */
    private static Long toEpoch(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (Exception ignored) {
            // try the next shape
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── type inference (port of inferColumns / inferType) ───────────────────────────

    private static Map<String, ColType> inferColumns(List<Map<String, Object>> rows) {
        Map<String, ColType> out = new LinkedHashMap<>();
        if (rows.isEmpty()) return out;
        for (String name : rows.get(0).keySet()) out.put(name, inferType(name, rows));
        return out;
    }

    private static ColType inferType(String name, List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            Object v = r.get(name);
            if (v == null || "".equals(v)) continue;
            if (v instanceof Number) return ColType.NUMBER;
            if (v instanceof Boolean) return ColType.BOOLEAN;
            String s = String.valueOf(v);
            if (s.matches("-?\\d+(\\.\\d+)?")) return ColType.NUMBER;
            if (s.matches("(?i)(true|false)")) return ColType.BOOLEAN;
            // date: needs a 4-digit year + a date/time separator, so plain ids don't read as dates
            if (s.matches(".*\\d{4}.*") && s.matches(".*[-/:T].*") && toEpoch(s) != null) return ColType.DATE;
            return ColType.STRING;
        }
        return ColType.STRING;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
