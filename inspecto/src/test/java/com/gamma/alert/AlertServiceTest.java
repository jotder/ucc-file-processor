package com.gamma.alert;

import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.ops.InMemoryObjectStore;
import com.gamma.ops.ObjectQuery;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectType;
import com.gamma.service.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alert engine's evaluation semantics (v4.1, B5): metric math over ledger rows, duration and
 * batch-count windows, pipeline scoping, and re-fire suppression. CPU-only — a fake StatusStore
 * serves hand-built ledger rows against a real loaded PipelineConfig.
 */
class AlertServiceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** A ledger row with the columns the metrics read. */
    private static Map<String, String> row(String status, long inRows, long outRows, long rejected,
                                           long durationMs, LocalDateTime endTime) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("total_input_rows", String.valueOf(inRows));
        m.put("total_output_rows", String.valueOf(outRows));
        m.put("rejected_count", String.valueOf(rejected));
        m.put("duration_ms", String.valueOf(durationMs));
        m.put("end_time", TS.format(endTime));
        return m;
    }

    private static StatusStore store(List<Map<String, String>> batches) {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return batches; }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private static ConfigSource configs(PipelineConfig cfg) {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static AlertRule rule(String metric, String comparator, double threshold, String window,
                                  String onPipeline) {
        return new AlertRule("r-" + metric, metric, comparator, threshold, window, "WARNING", onPipeline);
    }

    @Test
    void errorRateOverDurationWindowFires(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, String>> ledger = new ArrayList<>();
        ledger.add(row("SUCCESS", 1000, 990, 0, 100, now.minusDays(2)));   // outside the 1h window
        ledger.add(row("SUCCESS", 1000, 900, 0, 100, now.minusMinutes(5))); // 10% error in-window
        AlertService svc = new AlertService(
                List.of(rule("error_rate", "gt", 0.05, "1h", null)), configs(cfg), store(ledger));

        List<Map<String, Object>> firedNow = svc.evaluateAll();
        assertEquals(1, firedNow.size());
        assertEquals(0.1, (double) firedNow.get(0).get("value"), 1e-9,
                "the stale row must not dilute the in-window rate");
        assertEquals("MINI_ETL", firedNow.get(0).get("pipeline"));
        assertEquals(1, svc.recent(10).size());
    }

    @Test
    void batchCountWindowAndScopingAndCooldown(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, String>> ledger = List.of(
                row("FAILED", 10, 0, 0, 100, now.minusMinutes(30)),
                row("FAILED", 10, 0, 0, 100, now.minusMinutes(20)),
                row("SUCCESS", 10, 10, 0, 100, now.minusMinutes(10)));

        AlertRule scopedAway = rule("failed_batches", "gte", 1, "2b", "other_pipeline");
        AlertRule lastTwo = rule("failed_batches", "gte", 2, "2b", "mini_etl"); // last 2 = 1 FAILED → no
        AlertRule lastThree = rule("failed_batches", "gte", 2, "3b", "MINI_ETL"); // 2 FAILED → fires
        AlertService svc = new AlertService(List.of(scopedAway, lastTwo, lastThree),
                configs(cfg), store(ledger));

        // Event-driven entry point: a terminal batch for this pipeline triggers evaluation.
        svc.onEvent(new BatchEvent("MINI_ETL", "B9", "SUCCESS", List.of(), 10, 100, 0, null, null, 0));
        List<Map<String, Object>> recent = svc.recent(10);
        assertEquals(1, recent.size(), "only the matching, breached rule fires");
        assertEquals("r-failed_batches", recent.get(0).get("rule"));

        // Same condition immediately after: suppressed by the cooldown, not duplicated.
        svc.onEvent(new BatchEvent("MINI_ETL", "B10", "SUCCESS", List.of(), 10, 100, 0, null, null, 0));
        assertEquals(1, svc.recent(10).size(), "re-fire suppressed while in cooldown");
    }

    @Test
    void durationAndRejectedMetrics(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, String>> ledger = List.of(
                row("SUCCESS", 10, 10, 3, 4_000, now.minusMinutes(2)),
                row("SUCCESS", 10, 10, 2, 6_000, now.minusMinutes(1)));
        AlertService svc = new AlertService(List.of(
                rule("duration_ms", "gte", 5_000, "1h", null),     // avg = 5000 → fires
                rule("rejected_files", "gt", 5, "1h", null)),      // sum = 5 → gt 5 is false
                configs(cfg), store(ledger));

        List<Map<String, Object>> fired = svc.evaluateAll();
        assertEquals(1, fired.size());
        assertEquals("r-duration_ms", fired.get(0).get("rule"));
        assertTrue(((String) fired.get(0).get("message")).contains("duration_ms"));
    }

    // ── row-scoping 'when' (Rules triad condition-tree promotion, 2026-07-18) ──────

    private static Map<String, Object> when(String field, String operator, String value) {
        return Map.of("kind", "group", "op", "AND", "items", List.of(
                Map.of("kind", "condition", "field", field, "operator", operator, "value", value)));
    }

    @Test
    void whenScopesTheLedgerRowsTheMetricSees(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        LocalDateTime now = LocalDateTime.now();
        // Two batches in-window: one heavily rejected, one clean. duration_ms scoped to rejected>0
        // rows only should see just the first (10_000ms), not the average of both (6_000ms).
        List<Map<String, String>> ledger = List.of(
                row("SUCCESS", 10, 10, 5, 10_000, now.minusMinutes(2)),
                row("SUCCESS", 10, 10, 0, 2_000, now.minusMinutes(1)));

        AlertRule scoped = new AlertRule("r-scoped", "duration_ms", "gte", 9_000, "1h", "WARNING", null,
                null, null, when("rejected_count", ">", "0"));
        AlertService svc = new AlertService(List.of(scoped), configs(cfg), store(ledger));

        List<Map<String, Object>> fired = svc.evaluateAll();
        assertEquals(1, fired.size(), "scoped to the one rejected-row batch, avg=10000 >= 9000 fires");
        assertEquals(10_000.0, (double) fired.get(0).get("value"), 1e-9);
    }

    @Test
    void whenScopingToNoMatchingRowsDoesNotFire(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        List<Map<String, String>> ledger = List.of(
                row("SUCCESS", 10, 10, 0, 10_000, LocalDateTime.now().minusMinutes(1)));
        AlertRule scoped = new AlertRule("r-scoped", "duration_ms", "gte", 1, "1h", "WARNING", null,
                null, null, when("rejected_count", ">", "0"));
        AlertService svc = new AlertService(List.of(scoped), configs(cfg), store(ledger));

        assertEquals(0, svc.evaluateAll().size(), "no ledger row matches 'when' — nothing to breach");
    }

    // ── signal→Incident promotion (critical/error breaches enter triage) ─────────────

    private static int count(ObjectService objects, ObjectType type) {
        return objects.query(ObjectQuery.builder().objectType(type).build()).size();
    }

    @Test
    void criticalBreachPromotesToIncidentAlongsideTheAlert(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        List<Map<String, String>> ledger = List.of(
                row("FAILED", 10, 0, 0, 100, LocalDateTime.now().minusMinutes(5)));
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        AlertRule critical = new AlertRule("r-crit", "failed_batches", "gte", 1, "1h", "critical", "MINI_ETL");
        AlertService svc = new AlertService(List.of(critical), configs(cfg), store(ledger), objects);

        assertEquals(1, svc.evaluateAll().size(), "the critical rule breaches");
        assertEquals(1, count(objects, ObjectType.ALERT), "the ALERT object is still recorded");
        assertEquals(1, count(objects, ObjectType.INCIDENT), "a critical breach also opens an Incident");
    }

    @Test
    void warningBreachStaysAnAlertWithNoIncident(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        List<Map<String, String>> ledger = List.of(
                row("FAILED", 10, 0, 0, 100, LocalDateTime.now().minusMinutes(5)));
        ObjectService objects = new ObjectService(new InMemoryObjectStore());
        // rule() builds a WARNING-severity rule.
        AlertService svc = new AlertService(List.of(rule("failed_batches", "gte", 1, "1h", "MINI_ETL")),
                configs(cfg), store(ledger), objects);

        assertEquals(1, svc.evaluateAll().size(), "the warning rule breaches");
        assertEquals(1, count(objects, ObjectType.ALERT), "it records an ALERT object");
        assertEquals(0, count(objects, ObjectType.INCIDENT), "but a warning breach does not open an Incident");
    }
}
