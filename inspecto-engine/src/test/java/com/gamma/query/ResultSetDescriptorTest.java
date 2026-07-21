package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity guard for {@link ResultSetDescriptor} (W4) — the Java mirror of the UI
 * {@code inspecto/viz/result-set.ts}: JDBC-type → coarse-type mapping, role inference (date⇒temporal,
 * non-id number⇒measure, else dimension), cardinality, and the coarse rendering candidates.
 */
class ResultSetDescriptorTest {

    @Test
    void mapsJdbcTypes() {
        assertEquals("number", ResultSetDescriptor.columnType(Types.BIGINT));
        assertEquals("number", ResultSetDescriptor.columnType(Types.DECIMAL));
        assertEquals("string", ResultSetDescriptor.columnType(Types.VARCHAR));
        assertEquals("date", ResultSetDescriptor.columnType(Types.TIMESTAMP));
        assertEquals("boolean", ResultSetDescriptor.columnType(Types.BOOLEAN));
    }

    @Test
    void infersRolesAndCardinality() {
        List<String> names = List.of("id", "label", "amount", "day");
        List<Integer> types = List.of(Types.BIGINT, Types.VARCHAR, Types.DOUBLE, Types.DATE);
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1, "label", "a", "amount", 10.0, "day", "2026-07-01"),
                Map.of("id", 2, "label", "a", "amount", 20.0, "day", "2026-07-02"));

        List<ResultSetDescriptor.Column> cols = ResultSetDescriptor.describe(names, types, rows);
        assertEquals("dimension", byName(cols, "id").role(), "an *_id number is a dimension, never a measure");
        assertEquals("dimension", byName(cols, "label").role());
        assertEquals("measure", byName(cols, "amount").role());
        assertEquals("temporal", byName(cols, "day").role());
        assertEquals(1, byName(cols, "label").cardinality(), "two rows, one distinct label");
        assertNull(byName(cols, "amount").cardinality(), "measures carry no cardinality");
    }

    @Test
    void renderingsFromRoles() {
        List<ResultSetDescriptor.Column> cols = List.of(
                new ResultSetDescriptor.Column("label", "string", "dimension", 3),
                new ResultSetDescriptor.Column("region", "string", "dimension", 4),
                new ResultSetDescriptor.Column("day", "date", "temporal", null),
                new ResultSetDescriptor.Column("amount", "number", "measure", null));
        List<String> r = ResultSetDescriptor.renderings(cols);
        assertEquals("table", r.get(0), "table is always the first candidate");
        assertTrue(r.contains("line-chart"), "temporal + measure ⇒ line-chart");
        assertTrue(r.contains("bar-chart"), "dimension + measure ⇒ bar-chart");
        assertTrue(r.contains("heatmap"), "2 dimensions + measure ⇒ heatmap");
        assertFalse(r.contains("kpi"), "kpi only for a lone measure");
    }

    private static ResultSetDescriptor.Column byName(List<ResultSetDescriptor.Column> cols, String name) {
        return cols.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }
}
