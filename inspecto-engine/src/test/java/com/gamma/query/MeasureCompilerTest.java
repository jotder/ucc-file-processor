package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The BI-7 spec→SQL compiler: aggregation set, measure ids, filter ops, identifier safety. */
class MeasureCompilerTest {

    private static MeasureCompiler.Spec parse(Map<String, Object> body) {
        return MeasureCompiler.parse(body, 500, 10_000);
    }

    @Test
    void compilesMeasuresWithDimensionsAndFilters() {
        MeasureCompiler.Spec spec = parse(Map.of(
                "dataset", "sales",
                "measures", List.of(Map.of("agg", "sum", "field", "amount"), Map.of("agg", "count")),
                "groupBy", List.of("region"),
                "filters", List.of(Map.of("field", "amount", "op", ">", "value", 10),
                        Map.of("field", "region", "op", "in", "value", List.of("EU", "US"))),
                "orderBy", List.of(Map.of("field", "sum_amount", "dir", "desc")),
                "limit", 50));
        assertEquals("SELECT \"region\", SUM(\"amount\") AS \"sum_amount\", COUNT(*) AS \"count\" "
                        + "FROM \"sales\" WHERE \"amount\" > 10 AND \"region\" IN ('EU', 'US') "
                        + "GROUP BY \"region\" ORDER BY \"sum_amount\" DESC LIMIT 50",
                MeasureCompiler.compile(spec));
    }

    @Test
    void measureIdsMatchTheUiConvention() {
        assertEquals("sum_amount", new MeasureCompiler.Measure("sum", "amount").id());
        assertEquals("count", new MeasureCompiler.Measure("count", null).id());
        assertEquals("countDistinct_user_id", new MeasureCompiler.Measure("countDistinct", "user_id").id());
    }

    @Test
    void stringLiteralsAreEscapedAndTypedLiteralsPassThrough() {
        MeasureCompiler.Spec spec = parse(Map.of(
                "dataset", "d", "measures", List.of(Map.of("agg", "count")),
                "filters", List.of(Map.of("field", "name", "op", "=", "value", "O'Brien"),
                        Map.of("field", "active", "op", "=", "value", true))));
        String sql = MeasureCompiler.compile(spec);
        assertTrue(sql.contains("\"name\" = 'O''Brien'"), sql);
        assertTrue(sql.contains("\"active\" = true"), sql);
    }

    @Test
    void rejectsUnsafeInput() {
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of(
                "dataset", "d", "measures", List.of(Map.of("agg", "sum", "field", "amount); DROP TABLE x--")))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of(
                "dataset", "d", "measures", List.of(Map.of("agg", "median", "field", "amount")))),
                "unknown aggregation");
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of(
                "dataset", "d", "groupBy", List.of("a b"))));
        assertThrows(IllegalArgumentException.class, () -> parse(Map.of("dataset", "d")),
                "needs a measure or groupBy");
        MeasureCompiler.Spec inSpec = parse(Map.of("dataset", "d",
                "measures", List.of(Map.of("agg", "count")),
                "filters", List.of(Map.of("field", "x", "op", "weird", "value", 1))));
        assertThrows(IllegalArgumentException.class, () -> MeasureCompiler.compile(inSpec), "unknown op");
    }

    @Test
    void nullSemanticsUseExplicitOps() {
        MeasureCompiler.Spec spec = parse(Map.of("dataset", "d",
                "measures", List.of(Map.of("agg", "count")),
                "filters", List.of(Map.of("field", "x", "op", "isNull"),
                        Map.of("field", "y", "op", "notNull"))));
        String sql = MeasureCompiler.compile(spec);
        assertTrue(sql.contains("\"x\" IS NULL") && sql.contains("\"y\" IS NOT NULL"), sql);
    }
}
