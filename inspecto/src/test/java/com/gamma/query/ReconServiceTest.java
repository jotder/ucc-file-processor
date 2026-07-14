package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReconService} — spec validation (the route's 422 surface), the server-built
 * comparison SQL executed against real DuckDB over inline {@code VALUES} relations, and the SQL port of
 * the UI's {@code withinTolerance} (reconciliation-types.ts, locked 2026-07-03): exact | absolute |
 * percent (left-relative, zero-left ⇒ exact), NULL-on-one-side is a mismatch, NULL-on-both matches,
 * NULL key values group and match ({@code IS NOT DISTINCT FROM}).
 */
class ReconServiceTest {

    /**
     * The reference fixture (mirrors the design doc's Mediation-vs-Billing example):
     * EU/voice matched-equal, EU/data matched outside 0.5% (|118-114|/118 = 3.39%), US/voice matched-equal,
     * MEA/voice only in A (missing_right), APAC/sms only in B (missing_left).
     */
    private static final String REL_A = "SELECT * FROM (VALUES "
            + "('EU','voice',100.0),('EU','voice',100.0),('EU','data',118.0),('US','voice',50.0),('MEA','voice',10.0)"
            + ") AS t(region, product, amount)";
    private static final String REL_B = "SELECT * FROM (VALUES "
            + "('EU','voice',200.0),('EU','data',114.0),('US','voice',50.0),('APAC','sms',7.0)"
            + ") AS t(region, product, amount)";

    private static ReconService.Spec spec() {
        return ReconService.Spec.of(
                List.of(side("a_ds", REL_A), side("b_ds", REL_B)),
                List.of("region", "product"),
                List.of(new ReconService.Measure("amount", "sum", "percent", 0.5)),
                true);
    }

    private static ReconService.Side side(String id, String rel) {
        return new ReconService.Side(id, rel, null, null);
    }

    // ── spec validation (the 422 surface) ──────────────────────────────────────────

    @Test
    void specValidationFailsClosed() {
        List<ReconService.Side> sides = List.of(side("a", REL_A), side("b", REL_B));
        ReconService.Measure ok = new ReconService.Measure("amount", "sum", "exact", 0);

        // dataset count / keys
        assertThrows(IllegalArgumentException.class, () ->
                ReconService.Spec.of(List.of(side("a", REL_A)), List.of("region"), List.of(ok), true));
        assertThrows(IllegalArgumentException.class, () ->
                ReconService.Spec.of(sides, List.of(), List.of(ok), true));
        assertThrows(IllegalArgumentException.class, () ->
                ReconService.Spec.of(sides, List.of("no-good"), List.of(ok), true));
        // measures
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(sides, List.of("region"),
                List.of(new ReconService.Measure("amount", "avg", "exact", 0)), true));
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(sides, List.of("region"),
                List.of(new ReconService.Measure("amount", "sum", "fuzzy", 0)), true));
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(sides, List.of("region"),
                List.of(new ReconService.Measure("amount", "sum", "percent", -1)), true));
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(sides, List.of("region"),
                List.of(new ReconService.Measure(ReconService.RECORDS, "sum", "exact", 0)), true));
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(sides, List.of("amount"),
                List.of(ok), true));
        // nothing to compare
        assertThrows(IllegalArgumentException.class, () ->
                ReconService.Spec.of(sides, List.of("region"), List.of(), false));
        // ExpressionGuard-rejected filter, unsafe columnMap
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(
                List.of(new ReconService.Side("a", REL_A, null, "SELECT 1"), side("b", REL_B)),
                List.of("region"), List.of(ok), true));
        assertThrows(IllegalArgumentException.class, () -> ReconService.Spec.of(
                List.of(new ReconService.Side("a", REL_A, Map.of("region", "a b"), null), side("b", REL_B)),
                List.of("region"), List.of(ok), true));
    }

    // ── run: grain rows + exact totals + exact summary ─────────────────────────────

    @Test
    void runComputesGrainTotalsAndSummary() throws Exception {
        ReconService.RunResult r = ReconService.run(spec(), 100);

        assertFalse(r.truncated());
        assertEquals(5, r.rows().size(), "5 key groups");
        // ORDER BY region, product NULLS LAST
        assertEquals(List.of("APAC", "EU", "EU", "MEA", "US"),
                r.rows().stream().map(row -> String.valueOf(key(row).get("region"))).toList());

        Map<String, Object> euData = row(r, "EU", "data");
        assertEquals(118.0, num(sideOf(euData, "a").get("amount")));
        assertEquals(114.0, num(sideOf(euData, "b").get("amount")));
        assertEquals(Boolean.TRUE, euData.get("inA"));
        assertEquals(Boolean.TRUE, euData.get("inB"));
        assertEquals(1L, longOf(sideOf(euData, "a").get(ReconService.RECORDS)));

        Map<String, Object> euVoice = row(r, "EU", "voice");
        assertEquals(200.0, num(sideOf(euVoice, "a").get("amount")), "SUM of the two 100.0 rows");
        assertEquals(2L, longOf(sideOf(euVoice, "a").get(ReconService.RECORDS)));

        Map<String, Object> mea = row(r, "MEA", "voice");
        assertEquals(Boolean.FALSE, mea.get("inB"));
        assertNull(sideOf(mea, "b").get("amount"));

        // exact per-side totals
        assertEquals(378.0, num(sideOf(r.totals(), "a").get("amount")));
        assertEquals(371.0, num(sideOf(r.totals(), "b").get("amount")));
        assertEquals(5L, longOf(sideOf(r.totals(), "a").get(ReconService.RECORDS)));
        assertEquals(4L, longOf(sideOf(r.totals(), "b").get(ReconService.RECORDS)));

        // exact summary
        assertEquals(5L, longOf(r.summary().get("groups")));
        assertEquals(3L, longOf(r.summary().get("matchedKeys")));
        Map<?, ?> byType = (Map<?, ?>) r.summary().get("byType");
        assertEquals(1L, longOf(byType.get("missing_right")), "MEA/voice only in A");
        assertEquals(1L, longOf(byType.get("missing_left")), "APAC/sms only in B");
        assertEquals(1L, longOf(byType.get("value_break")), "EU/data outside 0.5%");
    }

    @Test
    void grainTruncatesButSummaryStaysExact() throws Exception {
        ReconService.RunResult r = ReconService.run(spec(), 2);
        assertTrue(r.truncated());
        assertEquals(2, r.rows().size());
        assertEquals(5L, longOf(r.summary().get("groups")), "summary is computed without the grain LIMIT");
    }

    // ── breaks: the three sets + path scoping + type filter ────────────────────────

    @Test
    void breaksSetsPathScopingAndTypeFilter() throws Exception {
        Map<String, ReconService.BreakSet> all = ReconService.breaks(spec(), null, null, 100, 0);
        assertEquals(3, all.size());
        assertEquals(1, all.get("missing_right").rowCount());
        assertEquals("MEA", key(all.get("missing_right").rows().get(0)).get("region"));
        assertEquals(10.0, num(sideOf(all.get("missing_right").rows().get(0), "a").get("amount")));
        assertFalse(all.get("missing_right").rows().get(0).containsKey("b"), "missing_right carries side A only");
        assertEquals("APAC", key(all.get("missing_left").rows().get(0)).get("region"));
        ReconService.BreakSet vb = all.get("value_break");
        assertEquals(1, vb.rowCount());
        assertEquals("data", key(vb.rows().get(0)).get("product"));
        assertEquals(118.0, num(sideOf(vb.rows().get(0), "a").get("amount")));
        assertEquals(114.0, num(sideOf(vb.rows().get(0), "b").get("amount")));

        // path scoping: under region=EU there is no missing_right, one value_break
        Map<String, ReconService.BreakSet> eu = ReconService.breaks(spec(), Map.of("region", "EU"), null, 100, 0);
        assertEquals(0, eu.get("missing_right").rowCount());
        assertEquals(1, eu.get("value_break").rowCount());

        // type filter returns just that set; unknown path column / type fail closed
        assertEquals(List.of("value_break"),
                List.copyOf(ReconService.breaks(spec(), null, "value_break", 100, 0).keySet()));
        assertThrows(IllegalArgumentException.class, () ->
                ReconService.breaks(spec(), Map.of("amount", "1"), null, 100, 0));
        assertThrows(IllegalArgumentException.class, () ->
                ReconService.breaks(spec(), null, "bogus", 100, 0));
    }

    // ── withinTolerance parity (SQL port of the locked UI semantics) ───────────────

    @Test
    void tolerancePredicateMatchesUiSemantics() throws Exception {
        // absolute: boundary is within (<=), beyond is a break
        assertEquals(0, valueBreaks(100.0, 105.0, new ReconService.Measure("amount", "sum", "absolute", 5)));
        assertEquals(1, valueBreaks(100.0, 106.0, new ReconService.Measure("amount", "sum", "absolute", 5)));
        // percent: left-relative boundary within; zero left requires exact equality
        assertEquals(0, valueBreaks(100.0, 100.5, new ReconService.Measure("amount", "sum", "percent", 0.5)));
        assertEquals(1, valueBreaks(100.0, 100.6, new ReconService.Measure("amount", "sum", "percent", 0.5)));
        assertEquals(0, valueBreaks(0.0, 0.0, new ReconService.Measure("amount", "sum", "percent", 50)));
        assertEquals(1, valueBreaks(0.0, 1.0, new ReconService.Measure("amount", "sum", "percent", 50)));
        // exact
        assertEquals(0, valueBreaks(100.0, 100.0, new ReconService.Measure("amount", "sum", "exact", 0)));
        assertEquals(1, valueBreaks(100.0, 100.1, new ReconService.Measure("amount", "sum", "exact", 0)));
        // NULL aggregate on one side is a mismatch; on both sides it matches
        assertEquals(1, valueBreaksSql("SELECT 'x' AS k, CAST(NULL AS DOUBLE) AS amount",
                "SELECT 'x' AS k, 5.0 AS amount", new ReconService.Measure("amount", "sum", "percent", 99)));
        assertEquals(0, valueBreaksSql("SELECT 'x' AS k, CAST(NULL AS DOUBLE) AS amount",
                "SELECT 'x' AS k, CAST(NULL AS DOUBLE) AS amount", new ReconService.Measure("amount", "sum", "exact", 0)));
    }

    @Test
    void nullKeyValuesGroupAndMatch() throws Exception {
        String a = "SELECT * FROM (VALUES (CAST(NULL AS VARCHAR), 1.0), ('x', 2.0)) AS t(k, amount)";
        String b = "SELECT CAST(NULL AS VARCHAR) AS k, 1.0 AS amount";
        ReconService.Spec s = ReconService.Spec.of(List.of(side("a", a), side("b", b)), List.of("k"),
                List.of(new ReconService.Measure("amount", "sum", "exact", 0)), true);
        ReconService.RunResult r = ReconService.run(s, 100);
        assertEquals(2L, longOf(r.summary().get("groups")));
        assertEquals(1L, longOf(r.summary().get("matchedKeys")), "NULL keys match via IS NOT DISTINCT FROM");
        Map<?, ?> byType = (Map<?, ?>) r.summary().get("byType");
        assertEquals(1L, longOf(byType.get("missing_right")), "'x' is only in A");
        assertEquals(0L, longOf(byType.get("value_break")));
    }

    // ── columnMap + per-side filter ────────────────────────────────────────────────

    @Test
    void columnMapAndFilterApply() throws Exception {
        String relB = "SELECT * FROM (VALUES "
                + "('EU','voice',200.0),('EU','data',114.0),('US','voice',50.0),('APAC','sms',7.0)"
                + ") AS t(REGION_CD, PROD, AMT)";
        ReconService.Spec s = ReconService.Spec.of(
                List.of(new ReconService.Side("a_ds", REL_A, null, "amount > 20.0"),
                        new ReconService.Side("b_ds", relB,
                                Map.of("region", "REGION_CD", "product", "PROD", "amount", "AMT"), null)),
                List.of("region", "product"),
                List.of(new ReconService.Measure("amount", "sum", "percent", 0.5)),
                true);
        ReconService.RunResult r = ReconService.run(s, 100);
        // the filter drops A's MEA/voice (10.0) → no missing_right; mapping resolves B's physical names
        assertEquals(4L, longOf(r.summary().get("groups")));
        assertEquals(0L, longOf(((Map<?, ?>) r.summary().get("byType")).get("missing_right")));
        assertEquals(114.0, num(sideOf(row(r, "EU", "data"), "b").get("amount")));
    }

    // ── column inventory + auto-matches ────────────────────────────────────────────

    @Test
    void columnsInventoryAndMatches() throws Exception {
        String relB = "SELECT * FROM (VALUES ('EU','x',1.0)) AS t(REGION_CD, PROD, AMOUNT)";
        Map<String, Object> out = ReconService.columns(List.of(side("a_ds", REL_A), side("b_ds", relB)));

        List<?> datasets = (List<?>) out.get("datasets");
        assertEquals(2, datasets.size());
        assertEquals(3, ((List<?>) ((Map<?, ?>) datasets.get(0)).get("columns")).size());

        // only "amount"/"AMOUNT" share a normalized name across both sides
        List<?> matches = (List<?>) out.get("matches");
        assertEquals(1, matches.size(), String.valueOf(matches));
        Map<?, ?> amount = (Map<?, ?>) matches.get(0);
        assertEquals("amount", amount.get("name"));
        assertEquals(Boolean.TRUE, amount.get("numeric"));
        assertEquals("AMOUNT", ((Map<?, ?>) amount.get("columns")).get("b_ds"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** value_break count for one matched single-key group with the given aggregated sides. */
    private static int valueBreaks(double a, double b, ReconService.Measure m) throws Exception {
        return valueBreaksSql("SELECT 'x' AS k, " + a + " AS amount", "SELECT 'x' AS k, " + b + " AS amount", m);
    }

    private static int valueBreaksSql(String relA, String relB, ReconService.Measure m) throws Exception {
        ReconService.Spec s = ReconService.Spec.of(List.of(side("a", relA), side("b", relB)),
                List.of("k"), List.of(m), true);
        Map<?, ?> byType = (Map<?, ?>) ReconService.run(s, 10).summary().get("byType");
        return (int) longOf(byType.get("value_break"));
    }

    private static Map<String, Object> row(ReconService.RunResult r, String region, String product) {
        for (Map<String, Object> row : r.rows()) {
            Map<?, ?> key = key(row);
            if (region.equals(key.get("region")) && product.equals(key.get("product"))) return row;
        }
        throw new AssertionError("no grain row " + region + "/" + product + " in " + r.rows());
    }

    private static Map<?, ?> key(Map<String, Object> row) {
        return (Map<?, ?>) row.get("key");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sideOf(Map<String, Object> row, String side) {
        return (Map<String, Object>) row.get(side);
    }

    private static double num(Object v) {
        assertNotNull(v, "expected a numeric value");
        return ((Number) v).doubleValue();
    }

    private static long longOf(Object v) {
        assertNotNull(v, "expected a count");
        return ((Number) v).longValue();
    }
}
