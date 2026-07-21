package com.gamma.service;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentConfig.Input;
import com.gamma.enrich.EnrichmentConfig.Output;
import com.gamma.enrich.EnrichmentConfig.Triggers;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.BatchEventBus;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EnrichmentService} (M2) — the orchestration that wires the M0
 * enrichment engine to the M1 event bus (freshness) and scheduler (completeness):
 * event-scoped incremental recompute, scheduled full recompute, Stage-2 → Stage-2
 * chains, idempotency under event+schedule overlap, and end-to-end from a real
 * Stage-1 batch commit via {@link CollectorService}.
 */
class EnrichmentServiceTest {

    private static final String DAILY_COUNT =
            "SELECT event_type, year, month, day, COUNT(*) AS event_count "
            + "FROM input GROUP BY event_type, year, month, day";

    /** Seed a Stage-1-like Parquet tree: event_type/year/month/day partitions of (id). */
    private static void seedInput(Path root) throws Exception {
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " +
                    "('CALL','2020','04','03','C1')," +
                    "('CALL','2020','04','03','C2')," +
                    "('CALL','2020','04','04','C3')," +
                    "('SMS','2020','04','03','S1')) " +
                    "t(event_type,year,month,day,id)) " +
                    "TO '" + root.toString().replace("\\", "/") + "' " +
                    "(FORMAT PARQUET, PARTITION_BY (event_type,year,month,day), OVERWRITE_OR_IGNORE 1)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static EnrichmentConfig dailyKpi(String name, Path in, Path out, Triggers triggers) {
        return new EnrichmentConfig(name,
                new Input(in.toString().replace("\\", "/"), "PARQUET",
                        List.of("event_type", "year", "month", "day")),
                List.of(),
                new Output(out.toString().replace("\\", "/"), "PARQUET", "snappy",
                        List.of("event_type", "year", "month", "day")),
                DAILY_COUNT, triggers);
    }

    /** Read a Parquet output tree into {event_type|day → event_count}. */
    private static Map<String, Long> readCounts(Path outRoot, String measure) throws Exception {
        Map<String, Long> m = new HashMap<>();
        if (!Files.exists(outRoot)) return m;
        File db = DuckDbUtil.tempDbFile("vfy_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT event_type, day, " + measure + " AS v FROM read_parquet('"
                     + outRoot.toString().replace("\\", "/")
                     + "/**/*.parquet', hive_partitioning=true, hive_types_autocast=0)")) {
            while (rs.next()) m.put(rs.getString("event_type") + "|" + rs.getString("day"), rs.getLong("v"));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
        return m;
    }

    /** Poll {@code events} until one with {@code pipeline} appears, or the deadline passes. */
    private static boolean await(List<BatchEvent> events, String pipeline, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            synchronized (events) {
                if (events.stream().anyMatch(e -> pipeline.equals(e.pipeline()))) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    // ── T2.1 event trigger: freshness, scoped to the committed partitions ───────────

    @Test
    void eventTriggerRecomputesOnlyTheCommittedPartition(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig job = dailyKpi("DAILY", in, out, new Triggers("EVENTS", 0));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(job), bus, sched);
        try {
            es.start();
            // A Stage-1 batch committed just the CALL/2020/04/03 partition.
            bus.publish(new BatchEvent("EVENTS", "b1", "SUCCESS",
                    List.of("event_type=CALL/year=2020/month=04/day=03"), 2, 100L, 0));

            assertTrue(await(seen, "DAILY", 10_000), "enrichment should announce its own commit");
            Map<String, Long> counts = readCounts(out, "event_count");
            assertEquals(Map.of("CALL|03", 2L), counts,
                    "only the committed partition is recomputed (freshness, scoped)");
        } finally {
            sched.close();
            es.close();
        }
    }

    // ── T2.2 scheduled trigger: completeness, full window recompute ──────────────────

    @Test
    void scheduledTriggerRecomputesTheFullWindow(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig job = dailyKpi("DAILY_SCHED", in, out, new Triggers(null, 1));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(job), bus, sched);
        try {
            es.start();   // fires after a 1s interval
            assertTrue(await(seen, "DAILY_SCHED", 8_000), "scheduled recompute should run");
            Map<String, Long> counts = readCounts(out, "event_count");
            assertEquals(2L, counts.get("CALL|03"));
            assertEquals(1L, counts.get("CALL|04"));
            assertEquals(1L, counts.get("SMS|03"), "full window: every partition present");
        } finally {
            sched.close();
            es.close();
        }
    }

    // ── hot registration (POST /enrichment, v5.1.0): no restart needed ───────────────

    @Test
    void hotRegisteredJobFiresOnTheNextBatchEvent(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        // A fresh space: the service starts hosting ZERO jobs (always constructed since v5.1.0).
        EnrichmentService es = new EnrichmentService(List.of(), bus, sched);
        try {
            es.start();
            es.register(dailyKpi("DAILY_HOT", in, out, new Triggers("EVENTS", 0)));
            assertEquals(1, es.configs().size(), "hosted immediately, no restart");

            bus.publish(new BatchEvent("EVENTS", "b1", "SUCCESS",
                    List.of("event_type=CALL/year=2020/month=04/day=03"), 2, 100L, 0));
            assertTrue(await(seen, "DAILY_HOT", 10_000), "hot-registered job fires on the next event");
            assertEquals(Map.of("CALL|03", 2L), readCounts(out, "event_count"));
        } finally {
            sched.close();
            es.close();
        }
    }

    @Test
    void reRegisterReplacesByNameSoTheNewConfigFires(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out1 = dir.resolve("out1"), out2 = dir.resolve("out2");
        seedInput(in);
        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(), bus, sched);
        try {
            es.start();
            es.register(dailyKpi("DAILY_UPSERT", in, out1, new Triggers("EVENTS", 0)));
            // Stage save re-registers the same name — the replacement's output dir proves which ran.
            es.register(dailyKpi("DAILY_UPSERT", in, out2, new Triggers("EVENTS", 0)));
            assertEquals(1, es.configs().size(), "replaced, not duplicated");

            bus.publish(new BatchEvent("EVENTS", "b1", "SUCCESS",
                    List.of("event_type=CALL/year=2020/month=04/day=03"), 2, 100L, 0));
            assertTrue(await(seen, "DAILY_UPSERT", 10_000));
            assertEquals(Map.of("CALL|03", 2L), readCounts(out2, "event_count"),
                    "the replacement config ran");
            assertTrue(readCounts(out1, "event_count").isEmpty(), "the replaced config did not");
        } finally {
            sched.close();
            es.close();
        }
    }

    // ── unregister (2026-07-20): a deleted-on-disk job stops instead of running until restart ──

    @Test
    void unregisterStopsFurtherScheduledRecomputesAndRemovesTheJob(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig job = dailyKpi("DAILY_UNREG", in, out, new Triggers(null, 1));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(job), bus, sched);
        try {
            es.start();
            assertTrue(await(seen, "DAILY_UNREG", 8_000), "scheduled recompute should run at least once");

            assertTrue(es.unregister("DAILY_UNREG"));
            assertFalse(es.unregister("DAILY_UNREG"), "second unregister of an already-gone job is a no-op");
            assertTrue(es.configs().isEmpty(), "job no longer hosted");

            int seenAtUnregister;
            synchronized (seen) { seenAtUnregister = seen.size(); }
            Thread.sleep(2500);   // long enough for the 1s schedule to have fired again if it survived
            synchronized (seen) {
                assertEquals(seenAtUnregister, seen.size(),
                        "unregister must cancel the schedule timer, not just remove the job from the read surface");
            }
        } finally {
            sched.close();
            es.close();
        }
    }

    // ── re-arm on replace (2026-07-20): a changed schedule interval applies immediately ──

    @Test
    void reRegisterWithAFasterIntervalReArmsInsteadOfKeepingTheOriginal(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(), bus, sched);
        try {
            // A long original interval that would not fire within this test's patience …
            es.register(dailyKpi("DAILY_REARM", in, out, new Triggers(null, 3600)));
            // … replaced with a 1s interval; without re-arming, the 3600s timer would still be the one ticking.
            es.register(dailyKpi("DAILY_REARM", in, out, new Triggers(null, 1)));
            assertTrue(await(seen, "DAILY_REARM", 8_000),
                    "the replacement's faster interval must apply immediately, not only after a restart");
        } finally {
            sched.close();
            es.close();
        }
    }

    // ── T2.3 chains: a Stage-2 commit triggers a downstream Stage-2 job ──────────────

    @Test
    void chainFiresDownstreamEnrichment(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), outA = dir.resolve("outA"), outB = dir.resolve("outB");
        seedInput(in);
        EnrichmentConfig a = dailyKpi("STAGE2_A", in, outA, new Triggers("EVENTS", 0));
        // B reads A's output and re-aggregates; it fires on A's own commit event.
        EnrichmentConfig b = new EnrichmentConfig("STAGE2_B",
                new Input(outA.toString().replace("\\", "/"), "PARQUET",
                        List.of("event_type", "year", "month", "day")),
                List.of(),
                new Output(outB.toString().replace("\\", "/"), "PARQUET", "snappy",
                        List.of("event_type", "year", "month", "day")),
                "SELECT event_type, year, month, day, SUM(event_count) AS event_count "
                        + "FROM input GROUP BY event_type, year, month, day",
                new Triggers("STAGE2_A", 0));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(a, b), bus, sched);
        try {
            es.start();
            bus.publish(new BatchEvent("EVENTS", "b1", "SUCCESS",
                    List.of("event_type=CALL/year=2020/month=04/day=03"), 2, 100L, 0));

            assertTrue(await(seen, "STAGE2_B", 10_000), "downstream B should fire on A's commit");
            assertEquals(Map.of("CALL|03", 2L), readCounts(outB, "event_count"),
                    "chain carried A's scoped partition through to B");
        } finally {
            sched.close();
            es.close();
        }
    }

    // ── T2.4 idempotency under event + schedule overlap ──────────────────────────────

    @Test
    void idempotentUnderEventAndScheduleOverlap(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig job = dailyKpi("DAILY_BOTH", in, out, new Triggers("EVENTS", 1));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(job), bus, sched);
        try {
            es.start();   // scheduled full recompute every 1s …
            // … while a burst of events also recomputes the same partitions.
            for (int i = 0; i < 5; i++) {
                bus.publish(new BatchEvent("EVENTS", "b" + i, "SUCCESS", List.of(
                        "event_type=CALL/year=2020/month=04/day=03",
                        "event_type=CALL/year=2020/month=04/day=04",
                        "event_type=SMS/year=2020/month=04/day=03"), 4, 100L, 0));
            }
            Thread.sleep(2500);   // let several event + scheduled recomputes overlap
        } finally {
            sched.close();
            es.close();
        }
        // Per-job serialisation + OVERWRITE_OR_IGNORE → counts converge, never double.
        Map<String, Long> counts = readCounts(out, "event_count");
        assertEquals(2L, counts.get("CALL|03"));
        assertEquals(1L, counts.get("CALL|04"));
        assertEquals(1L, counts.get("SMS|03"));
    }

    // ── run-level audit/lineage persisted for an orchestrated recompute ──────────────

    @Test
    void recomputeWritesRunAuditLineageAndCommitLog(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig job = dailyKpi("DAILY_AUDIT", in, out, new Triggers("EVENTS", 0));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(job), bus, sched);
        try {
            es.start();
            bus.publish(new BatchEvent("EVENTS", "b1", "SUCCESS",
                    List.of("event_type=CALL/year=2020/month=04/day=03"), 2, 100L, 0));
            assertTrue(await(seen, "DAILY_AUDIT", 10_000), "recompute should complete");
        } finally {
            sched.close();
            es.close();
        }

        Path auditDir = Path.of(out + "_audit");
        List<String> runLines = Files.readAllLines(auditDir.resolve("daily_audit_enrich_runs.csv"));
        assertEquals(2, runLines.size(), "header + one run row");
        assertTrue(runLines.get(1).contains("DAILY_AUDIT"));
        assertTrue(runLines.get(1).contains("event"),   "event trigger recorded");
        assertTrue(runLines.get(1).contains("SUCCESS"));

        List<String> linLines = Files.readAllLines(auditDir.resolve("daily_audit_enrich_lineage.csv"));
        assertEquals(2, linLines.size(), "header + the one recomputed partition");
        assertTrue(linLines.get(1).contains("event_type=CALL/year=2020/month=04/day=03"));

        assertTrue(Files.exists(auditDir.resolve("daily_audit_enrich_commits.log")), "commit log written");
    }

    // ── read surface: views / runs / lineage reflect an orchestrated recompute ───────

    @Test
    void readSurfaceReflectsAnOrchestratedRecompute(@TempDir Path dir) throws Exception {
        Path in = dir.resolve("in"), out = dir.resolve("out");
        seedInput(in);
        EnrichmentConfig job = dailyKpi("DAILY_READ", in, out, new Triggers("EVENTS", 0));

        BatchEventBus bus = new BatchEventBus();
        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe(seen::add);
        Scheduler sched = new Scheduler();
        EnrichmentService es = new EnrichmentService(List.of(job), bus, sched);
        try {
            es.start();
            // before any run: job is listed but reports zero runs
            assertEquals(1, es.views().size());
            assertEquals("DAILY_READ", es.views().get(0).name());
            assertEquals(0, es.views().get(0).runCount());
            assertTrue(es.runs("DAILY_READ").isEmpty());

            bus.publish(new BatchEvent("EVENTS", "b1", "SUCCESS",
                    List.of("event_type=CALL/year=2020/month=04/day=03"), 2, 100L, 0));
            assertTrue(await(seen, "DAILY_READ", 10_000), "recompute should complete");
        } finally {
            sched.close();
            es.close();
        }

        // run audit now readable through the service's read surface
        List<Map<String, String>> runs = es.runs("DAILY_READ");
        assertEquals(1, runs.size(), "one run recorded");
        assertEquals("SUCCESS", runs.get(0).get("status"));
        assertEquals("event", runs.get(0).get("trigger"));
        String runId = runs.get(0).get("run_id");
        assertNotNull(runId);

        EnrichmentService.JobView v = es.views().get(0);
        assertEquals(1, v.runCount());
        assertTrue(v.eventTriggered());
        assertEquals("EVENTS", v.onPipeline());
        assertEquals("SUCCESS", v.lastStatus());

        // lineage carries the one recomputed partition; filtering by runId is exact
        List<Map<String, String>> lineage = es.lineage("DAILY_READ", null);
        assertEquals(1, lineage.size());
        assertEquals("event_type=CALL/year=2020/month=04/day=03", lineage.get(0).get("partition"));
        assertEquals(lineage, es.lineage("DAILY_READ", runId), "runId filter matches the only run");
        assertTrue(es.lineage("DAILY_READ", "no-such-run").isEmpty());

        // unknown job is rejected
        assertThrows(IllegalArgumentException.class, () -> es.runs("GHOST"));
    }

    // ── end-to-end: a real Stage-1 batch commit drives enrichment through CollectorService ─

    @Test
    void stage1CommitTriggersEnrichmentEndToEnd(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("src");
        Path toon = TestConfigs.csv(root, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = root.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");

        // Stage-1 writes CSV partitioned by year/month/day under <root>/db; enrichment
        // reads that and rolls up a daily count. Triggered by the pipeline's name (TEST_ETL).
        Path reports = root.resolve("reports");
        EnrichmentConfig job = new EnrichmentConfig("DAILY_KPI",
                new Input(root.resolve("db").toString().replace("\\", "/"), "CSV",
                        List.of("year", "month", "day")),
                List.of(),
                new Output(reports.toString().replace("\\", "/"), "CSV", null,
                        List.of("year", "month", "day")),
                "SELECT year, month, day, COUNT(*) AS n FROM input GROUP BY year, month, day",
                new Triggers("test_etl", 0));   // pipeline name is lower-cased by identity

        List<BatchEvent> seen = Collections.synchronizedList(new ArrayList<>());
        try (CollectorService svc = new CollectorService(List.of(toon), List.of(job), 3600, 1)) {
            svc.eventBus().subscribe(seen::add);
            // start() wires the enrichment subscriber and then schedules an immediate poll
            // (initialDelay 0); that single cycle commits Stage-1 → event → enrichment.
            svc.start();

            assertTrue(await(seen, "DAILY_KPI", 15_000),
                    "a Stage-1 batch commit should drive the enrichment to completion");
        }
        long csvOut;
        try (Stream<Path> s = Files.walk(reports)) {
            csvOut = s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".csv")).count();
        }
        assertTrue(csvOut >= 1, "enrichment produced report output, got " + csvOut);
    }
}
