package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Semantics contract for {@link ConditionTree} — the backend port of the UI's offline query
 * evaluator ({@code inspecto-ui/.../query/query-eval.ts}). Each case pins a rule the authoring UI
 * relies on so the two stay in lock-step: typed comparisons, case-insensitive substring ops,
 * {@code in}/{@code between}, null checks, AND/OR nesting, "empty/incomplete group ⇒ matches all",
 * and type inference from the sample.
 */
class ConditionTreeTest {

    // ── typed leaf operators ────────────────────────────────────────────────────────

    @Test
    void numericComparisonInfersNumberType() {
        List<Map<String, Object>> rows = List.of(
                row("region", "APAC", "cost", 9200),
                row("region", "EU", "cost", 50),
                row("region", "US", "cost", 5000));
        // cost > 5000 ⇒ only the 9200 row
        assertEquals(1, ConditionTree.matched(and(cond("cost", ">", "5000")), rows));
        // cost >= 5000 ⇒ 9200 and 5000
        assertEquals(2, ConditionTree.matched(and(cond("cost", ">=", "5000")), rows));
        // between 40 and 60 ⇒ the 50 row
        assertEquals(1, ConditionTree.matched(and(between("cost", "40", "60")), rows));
    }

    @Test
    void numericStringsCompareNumericallyNotLexically() {
        // "9" > "100" lexically, but 9 < 100 numerically — inference must pick number
        List<Map<String, Object>> rows = List.of(row("n", "9"), row("n", "100"));
        assertEquals(1, ConditionTree.matched(and(cond("n", ">", "50")), rows));
    }

    @Test
    void stringOpsAreCaseInsensitive() {
        List<Map<String, Object>> rows = List.of(
                row("name", "Alice"), row("name", "bob"), row("name", "Carol"));
        assertEquals(2, ConditionTree.matched(and(cond("name", "contains", "o")), rows)); // bob, Carol
        assertEquals(1, ConditionTree.matched(and(cond("name", "startsWith", "al")), rows)); // Alice
        assertEquals(1, ConditionTree.matched(and(cond("name", "endsWith", "OL")), rows)); // Carol
        assertEquals(1, ConditionTree.matched(and(cond("name", "=", "bob")), rows));
        assertEquals(2, ConditionTree.matched(and(cond("name", "!=", "Alice")), rows));
    }

    @Test
    void inSplitsCommaListAndMatchesAny() {
        List<Map<String, Object>> rows = List.of(
                row("cc", "US"), row("cc", "FR"), row("cc", "ZZ"));
        assertEquals(2, ConditionTree.matched(and(cond("cc", "in", "US, FR")), rows));
    }

    @Test
    void booleanEqualityInfersBooleanType() {
        List<Map<String, Object>> rows = List.of(
                row("flag", true), row("flag", false), row("flag", "true"));
        assertEquals(2, ConditionTree.matched(and(cond("flag", "=", "true")), rows));
    }

    @Test
    void dateBetweenInfersDateType() {
        List<Map<String, Object>> rows = List.of(
                row("d", "2025-01-15"), row("d", "2026-06-20"), row("d", "2027-01-01"));
        assertEquals(1, ConditionTree.matched(and(between("d", "2026-01-01", "2026-12-31")), rows));
    }

    @Test
    void nullChecks() {
        List<Map<String, Object>> rows = List.of(
                row("email", "a@x.com"), row("email", (Object) null), row("email", ""));
        assertEquals(2, ConditionTree.matched(and(cond("email", "isNull", null)), rows)); // null + ""
        assertEquals(1, ConditionTree.matched(and(cond("email", "isNotNull", null)), rows));
    }

    // ── group semantics ─────────────────────────────────────────────────────────────

    @Test
    void andRequiresAllOrRequiresAny() {
        List<Map<String, Object>> rows = List.of(
                row("region", "APAC", "cost", 9200),
                row("region", "APAC", "cost", 10),
                row("region", "EU", "cost", 9000));
        Object hiApac = and(cond("region", "=", "APAC"), cond("cost", ">", "5000"));
        assertEquals(1, ConditionTree.matched(hiApac, rows));
        Object apacOrHi = or(cond("region", "=", "APAC"), cond("cost", ">", "5000"));
        assertEquals(3, ConditionTree.matched(apacOrHi, rows));
    }

    @Test
    void nestedGroupsEvaluateRecursively() {
        List<Map<String, Object>> rows = List.of(
                row("region", "APAC", "cost", 9200, "vip", true),
                row("region", "EU", "cost", 100, "vip", true),
                row("region", "US", "cost", 100, "vip", false));
        // vip AND (cost > 5000 OR region = EU)
        Object tree = and(cond("vip", "=", "true"),
                or(cond("cost", ">", "5000"), cond("region", "=", "EU")));
        assertEquals(2, ConditionTree.matched(tree, rows));
    }

    @Test
    void emptyOrIncompleteTreeMatchesAllRows() {
        List<Map<String, Object>> rows = List.of(row("a", 1), row("a", 2), row("a", 3));
        assertEquals(3, ConditionTree.matched(and(), rows), "empty group ⇒ no constraint");
        assertEquals(3, ConditionTree.matched(null, rows), "absent tree ⇒ no constraint");
        // an incomplete leaf (no value) contributes nothing ⇒ still matches all
        assertEquals(3, ConditionTree.matched(and(cond("a", ">", "")), rows));
    }

    @Test
    void incompleteLeafSkippedButSiblingApplies() {
        List<Map<String, Object>> rows = List.of(row("a", 1), row("a", 9));
        // one complete (a > 5) + one incomplete (no value) ⇒ only the complete one constrains
        Object tree = and(cond("a", ">", "5"), cond("a", "<", ""));
        assertEquals(1, ConditionTree.matched(tree, rows));
    }

    @Test
    void emptySampleCountsZero() {
        assertEquals(0, ConditionTree.matched(and(cond("a", ">", "1")), List.of()));
    }

    // ── builders (mirror the query-types.ts JSON shape) ─────────────────────────────

    private static Object and(Object... items) {
        return group("AND", items);
    }

    private static Object or(Object... items) {
        return group("OR", items);
    }

    private static Object group(String op, Object... items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("kind", "group");
        g.put("op", op);
        g.put("items", List.of(items));
        return g;
    }

    private static Object cond(String field, String operator, String value) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "condition");
        c.put("field", field);
        c.put("operator", operator);
        if (value != null) c.put("value", value);
        return c;
    }

    private static Object between(String field, String lo, String hi) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "condition");
        c.put("field", field);
        c.put("operator", "between");
        c.put("value", lo);
        c.put("value2", hi);
        return c;
    }

    /** A row map that tolerates null values (unlike {@code Map.of}). */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
