package com.gamma.query;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity tests: for every operator, {@link ConditionSql#predicate} evaluated by DuckDB over a table
 * must match the rows {@link ConditionTree#matched} counts over the same data in-JVM — the engine-side
 * rule application ({@code DecisionRuleApplier}) and the {@code simulate} preview must agree.
 */
class ConditionSqlTest {

    private static Connection conn;
    private static File db;

    /** The same five rows as a DuckDB table and as the in-JVM sample. */
    private static final List<Map<String, Object>> ROWS = rows();

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(row("alice", 250.0, 30, "2026-07-01", "SHIPPED"));
        out.add(row("bob", 50.0, 90, "2026-07-02", "NEW"));
        out.add(row("carol", 999.0, 5, "2026-07-03", "SHIPPED"));
        out.add(row("dave", 100.0, 60, "2026-07-04", null));
        out.add(row("erin high-cost", 101.5, 59, "2026-07-05", "CANCELLED"));
        return out;
    }

    private static Map<String, Object> row(String name, double cost, int dur, String day, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("cost", cost);
        r.put("dur", dur);
        r.put("day", day);
        r.put("status", status);
        return r;
    }

    @BeforeAll
    static void openDb() throws Exception {
        DuckDbUtil.loadDriver();
        db = DuckDbUtil.tempDbFile("condition_sql_");
        conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            StringBuilder sql = new StringBuilder(
                    "CREATE TABLE t AS SELECT * FROM (VALUES ");
            for (int i = 0; i < ROWS.size(); i++) {
                Map<String, Object> r = ROWS.get(i);
                sql.append(i > 0 ? ", (" : "(")
                        .append("'").append(r.get("name")).append("', ")
                        .append(r.get("cost")).append(", ")
                        .append(r.get("dur")).append(", ")
                        .append("'").append(r.get("day")).append("', ")
                        .append(r.get("status") == null ? "NULL" : "'" + r.get("status") + "'")
                        .append(")");
            }
            sql.append(") v(name, cost, dur, day, status)");
            st.execute(sql.toString());
        }
    }

    @AfterAll
    static void closeDb() throws Exception {
        if (conn != null) conn.close();
        if (db != null) DuckDbUtil.deleteTempDb(db);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> cond(String field, String operator, String value) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "condition");
        c.put("field", field);
        c.put("operator", operator);
        c.put("value", value);
        return c;
    }

    private static Map<String, Object> group(String op, Object... items) {
        return Map.of("kind", "group", "op", op, "items", List.of(items));
    }

    private long sqlMatched(Object when) throws Exception {
        String pred = ConditionSql.predicate(when);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t WHERE " + pred)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Assert DuckDB and ConditionTree agree, and that the count is the expected one. */
    private void assertParity(int expected, Object when) throws Exception {
        assertEquals(expected, ConditionTree.matched(when, ROWS), "ConditionTree count");
        assertEquals(expected, sqlMatched(when), "SQL count for: " + ConditionSql.predicate(when));
    }

    // ── operator parity ─────────────────────────────────────────────────────────

    @Test
    void numericComparisons() throws Exception {
        assertParity(3, group("AND", cond("cost", ">", "100")));          // 250, 999, 101.5
        assertParity(4, group("AND", cond("cost", ">=", "100")));
        assertParity(1, group("AND", cond("cost", "<", "100")));
        assertParity(2, group("AND", cond("dur", "<=", "30")));
        assertParity(1, group("AND", cond("cost", "=", "50")));
        assertParity(4, group("AND", cond("cost", "!=", "50")));
    }

    @Test
    void stringEqualityIsCaseSensitive() throws Exception {
        assertParity(1, group("AND", cond("name", "=", "alice")));
        assertParity(0, group("AND", cond("name", "=", "ALICE")));
        assertParity(2, group("AND", cond("status", "=", "SHIPPED")));
    }

    @Test
    void substringOpsAreCaseInsensitive() throws Exception {
        assertParity(1, group("AND", cond("name", "contains", "HIGH-COST")));
        assertParity(1, group("AND", cond("name", "startsWith", "Ali")));
        assertParity(1, group("AND", cond("name", "endsWith", "B")));    // bob (case-insensitive)
    }

    @Test
    void inAndBetween() throws Exception {
        assertParity(2, group("AND", cond("name", "in", "alice, bob")));
        assertParity(3, group("AND", cond("cost", "between", "100", "300")));   // 250, 100, 101.5 (inclusive)
    }

    @Test
    void nullChecks() throws Exception {
        assertParity(1, group("AND", cond("status", "isNull", "")));
        assertParity(4, group("AND", cond("status", "isNotNull", "")));
    }

    @Test
    void dateComparison() throws Exception {
        assertParity(3, group("AND", cond("day", ">=", "2026-07-03")));
    }

    @Test
    void nestedGroups() throws Exception {
        // carol (999) ∪ {alice, carol} (SHIPPED with dur ≤ 30) = {alice, carol}
        assertParity(2, group("OR",
                cond("cost", ">", "500"),
                group("AND", cond("status", "=", "SHIPPED"), cond("dur", "<=", "30"))));
    }

    @Test
    void emptyGroupMatchesAll() throws Exception {
        assertParity(ROWS.size(), group("AND"));
        assertParity(ROWS.size(), null);
    }

    @Test
    void incompleteLeafContributesNothing() throws Exception {
        assertParity(3, group("AND", cond("cost", ">", "100"), cond("", ">", "5")));
    }

    private static Map<String, Object> cond(String field, String operator, String value, String value2) {
        Map<String, Object> c = cond(field, operator, value);
        c.put("value2", value2);
        return c;
    }
}
