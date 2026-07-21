package com.gamma.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReconConfigLoader#buildSpec} — the shared {@code reconciliation} config → {@link ReconService.Spec}
 * assembly used by both the interactive route and the scheduled {@code recon.run} Job. Proves it builds a
 * <em>runnable</em> spec (fed straight into {@link ReconService#run}), honours the v1 {@code leftDataset}/
 * {@code rightDataset} keys, and rejects an unusable dataset count.
 */
class ReconConfigLoaderTest {

    /** Two tiny inline sides keyed by region, measure amount: EU matches, US only-in-a, APAC only-in-b. */
    private static String rel(String id) {
        return switch (id) {
            case "a" -> "SELECT * FROM (VALUES ('EU',100.0),('US',50.0)) t(region, amount)";
            case "b" -> "SELECT * FROM (VALUES ('EU',100.0),('APAC',7.0)) t(region, amount)";
            default -> throw new IllegalArgumentException("unknown dataset '" + id + "'");
        };
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsARunnableSpecFromAConfigMap() throws Exception {
        Map<String, Object> config = Map.of(
                "datasets", List.of("a", "b"),
                "keyColumns", List.of("region"),
                "compareColumns", List.of(Map.of("column", "amount", "toleranceType", "exact")));

        ReconService.Spec spec = ReconConfigLoader.buildSpec(config, ReconConfigLoaderTest::rel);
        assertEquals(2, spec.sides().size());
        assertEquals(List.of("region"), spec.keyColumns());
        assertEquals("amount", spec.measures().get(0).name());

        ReconService.RunResult r = ReconService.run(spec, 100);
        Map<String, Object> byType = (Map<String, Object>) r.summary().get("byType");
        assertEquals(1L, ((Number) byType.get("missing_right")).longValue(), "US is only in the anchor (a)");
        assertEquals(1L, ((Number) byType.get("missing_left")).longValue(), "APAC is only in b");
        assertEquals(0L, ((Number) byType.get("value_break")).longValue(), "EU matches exactly");
    }

    @Test
    void v1LeftRightDatasetKeysAreAccepted() {
        Map<String, Object> config = Map.of(
                "leftDataset", "a", "rightDataset", "b",
                "keyColumns", List.of("region"),
                "compareColumns", List.of(Map.of("column", "amount")));

        ReconService.Spec spec = ReconConfigLoader.buildSpec(config, ReconConfigLoaderTest::rel);
        assertEquals("a", spec.sides().get(0).datasetId());
        assertEquals("b", spec.sides().get(1).datasetId());
    }

    @Test
    void wrongDatasetCountThrows() {
        Map<String, Object> one = Map.of("datasets", List.of("a"),
                "keyColumns", List.of("region"), "compareColumns", List.of(Map.of("column", "amount")));
        assertThrows(IllegalArgumentException.class,
                () -> ReconConfigLoader.buildSpec(one, ReconConfigLoaderTest::rel));
    }
}
