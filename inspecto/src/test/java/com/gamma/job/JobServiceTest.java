package com.gamma.job;

import com.gamma.etl.BatchEvent;
import com.gamma.service.BatchEventBus;
import com.gamma.service.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link JobService} registry/scheduler: manual / cron / event triggers,
 * the built-in maintenance tasks, run audit + history, and the API listing.
 */
class JobServiceTest {

    private static JobConfig maintenance(String name, String cron, String onPipeline, Map<String, String> params) {
        return new JobConfig(name, JobType.MAINTENANCE, cron, onPipeline, true, false, params);
    }

    /** Poll until a run for {@code name} appears (or fail after 6s). */
    /** Poll until the supplier yields a non-null run (or 10s elapse). Generous timeout so the
     *  "eventually fires" assertions don't race a loaded CI runner; not the tight critical path. */
    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 10s");
        return r;
    }

    @Test
    void manualTriggerRunsRecordsAndAudits(@TempDir Path dir) throws Exception {
        JobConfig hb = maintenance("hb", null, null, Map.of("task", "heartbeat"));
        Path auditDir = dir.resolve("jobs_audit");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new BatchEventBus(), s, null, auditDir.toString())) {
            js.start();
            assertTrue(js.trigger("hb"), "known job triggers");
            JobRun run = await(() -> js.lastRunOf("hb").orElse(null));
            assertEquals("SUCCESS", run.status());
            assertEquals("manual", run.trigger());
            assertEquals("MAINTENANCE", run.type());
            assertEquals(1, js.runsFor("hb").size(), "one run in history");
            assertTrue(Files.exists(auditDir.resolve("jobs_runs.csv")), "durable audit written");
            assertTrue(Files.readString(auditDir.resolve("jobs_runs.csv")).contains("hb"));
        }
    }

    @Test
    void cleanupDeletesFilesOlderThanRetention(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("target");
        Files.createDirectories(target);
        Path old = target.resolve("old.csv");
        Path fresh = target.resolve("new.csv");
        Files.writeString(old, "x");
        Files.writeString(fresh, "y");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(10))));

        JobConfig clean = maintenance("clean", null, null,
                Map.of("task", "cleanup", "dir", target.toString(), "retention_days", "1", "glob", "*.csv"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(clean), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            js.trigger("clean");
            JobRun run = await(() -> js.lastRunOf("clean").orElse(null));
            assertEquals("SUCCESS", run.status());
            assertFalse(Files.exists(old), "stale file deleted");
            assertTrue(Files.exists(fresh), "recent file kept");
            assertTrue(run.message().contains("deleted 1"), "message reports the deletion: " + run.message());
        }
    }

    @Test
    void cronTriggerFires(@TempDir Path dir) throws Exception {
        JobConfig tick = maintenance("tick", "* * * * * *", null, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(tick), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            // An every-second cron can re-fire while the first run is still in flight; that
            // overlap is (correctly) recorded as SKIPPED. Assert on the first SUCCESSful fire,
            // not merely the latest run — lastRunOf() may return a SKIPPED re-fire on a slow runner.
            JobRun run = await(() -> js.runsFor("tick").stream()
                    .filter(r -> "SUCCESS".equals(r.status())).findFirst().orElse(null));
            assertEquals("schedule", run.trigger(), "cron fire records the schedule trigger");
            assertEquals("SUCCESS", run.status());
        }
    }

    @Test
    void eventTriggerFiresOnUpstreamCommit(@TempDir Path dir) throws Exception {
        BatchEventBus bus = new BatchEventBus();
        JobConfig ev = maintenance("ev", null, "UPSTREAM", Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(ev), bus, s, null, dir.resolve("audit").toString())) {
            js.start();
            bus.publish(new BatchEvent("UPSTREAM", "b1", "SUCCESS", List.of("p=1"), 1L, 1L, 0));
            JobRun run = await(() -> js.lastRunOf("ev").orElse(null));
            assertTrue(run.trigger().startsWith("event:UPSTREAM"), "event trigger recorded: " + run.trigger());
        }
    }

    @Test
    void listingReportsScheduleAndNextFire(@TempDir Path dir) throws Exception {
        JobConfig nf = maintenance("nf", "0 2 * * *", null, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(nf), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            List<JobService.JobView> views = js.jobs();
            assertEquals(1, views.size());
            JobService.JobView v = views.get(0);
            assertEquals("nf", v.name());
            assertEquals("0 2 * * *", v.cron());
            assertTrue(v.enabled());
            assertFalse(v.nextFire().isBlank(), "a cron job shows its next fire time");
        }
    }

    @Test
    void unknownJobDoesNotTrigger(@TempDir Path dir) throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            assertFalse(js.trigger("nope"));
            assertTrue(js.runsFor("nope").isEmpty());
        }
    }

    @Test
    void disabledJobIsNotBuiltOrScheduled(@TempDir Path dir) throws Exception {
        JobConfig off = new JobConfig("off", JobType.MAINTENANCE, "* * * * * *", null, false, false,
                Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(off), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            assertFalse(js.has("off"), "disabled job is not registered");
            assertFalse(js.trigger("off"), "disabled job cannot be triggered");
            // it still appears in the listing (for visibility) but with no next fire
            assertEquals(1, js.jobs().size());
            assertTrue(js.jobs().get(0).nextFire().isBlank());
        }
    }

    // ── T26: misfire / catch-up ────────────────────────────────────────────────

    /** Seed a durable jobs_runs.csv with one prior run for {@code job} at {@code startTime} (yyyy-MM-dd HH:mm:ss). */
    private static void seedAudit(Path auditDir, String job, String startTime) throws Exception {
        Files.createDirectories(auditDir);
        Files.writeString(auditDir.resolve("jobs_runs.csv"),
                "run_id,job,type,trigger,start_time,end_time,status,duration_ms,message\n"
                        + "r1," + job + ",MAINTENANCE,manual," + startTime + "," + startTime + ",SUCCESS,5,\"ok\"\n");
    }

    @Test
    void catchesUpAMissedCronFireOnStartup(@TempDir Path dir) throws Exception {
        Path auditDir = dir.resolve("audit");
        seedAudit(auditDir, "nightly", "2000-01-01 00:00:00");   // last ran long ago
        JobConfig nightly = new JobConfig("nightly", JobType.MAINTENANCE, "0 0 * * *", null, true, true,
                Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(nightly), new BatchEventBus(), s, null, auditDir.toString())) {
            js.start();   // a daily fire has elapsed since 2000 → catch-up runs once
            JobRun run = await(() -> js.lastRunOf("nightly").orElse(null));
            assertEquals("catch-up", run.trigger());
            assertEquals("SUCCESS", run.status());
        }
    }

    @Test
    void consultsTheDeletionFenceForADeleteJobDeclaringAStore(@TempDir Path dir) throws Exception {
        // a maintenance job (heartbeat so it runs cleanly) that declares the store it would delete
        JobConfig del = new JobConfig("purge", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "heartbeat", "store", "orders"));
        java.util.concurrent.atomic.AtomicReference<java.util.Collection<String>> asked =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(del), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.deletionGuard(stores -> { asked.set(stores); return List.of(); });
            js.start();
            js.trigger("purge");
            await(() -> js.lastRunOf("purge").orElse(null));
            assertNotNull(asked.get(), "the fence was consulted before the delete job ran");
            assertTrue(asked.get().contains("orders"), "the declared store was passed to the fence");
        }
    }

    @Test
    void writesRunsThroughToTheReportingStore(@TempDir Path dir) throws Exception {
        JobConfig hb = maintenance("hb", null, null, Map.of("task", "heartbeat"));
        DbJobRunStore store = DbJobRunStore.open("jdbc:duckdb:");   // in-memory; closed by js.close()
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString(), store)) {
            js.start();
            assertSame(store, js.runStore().orElseThrow(), "the store is exposed for the API");
            js.trigger("hb");
            await(() -> js.lastRunOf("hb").orElse(null));
            assertTrue(((Number) store.metrics("hb").get("total")).longValue() >= 1,
                    "the run was projected into the DuckDB reporting store");
        }
    }

    @Test
    void noCatchUpWithoutTheFlagOrBaseline(@TempDir Path dir) throws Exception {
        Path auditDir = dir.resolve("audit");
        seedAudit(auditDir, "noflag", "2000-01-01 00:00:00");          // stale, but catch_up:false below
        JobConfig noFlag = new JobConfig("noflag", JobType.MAINTENANCE, "0 0 * * *", null, true, false,
                Map.of("task", "heartbeat"));
        JobConfig fresh  = new JobConfig("fresh", JobType.MAINTENANCE, "0 0 * * *", null, true, true,
                Map.of("task", "heartbeat"));   // catch_up:true but no prior run in the audit → no baseline
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(noFlag, fresh), new BatchEventBus(), s, null, auditDir.toString())) {
            js.start();
            Thread.sleep(300);   // give any erroneous catch-up submit time to surface
            assertTrue(js.runsFor("noflag").isEmpty(), "catch_up:false does not catch up");
            assertTrue(js.runsFor("fresh").isEmpty(), "no prior run = no baseline = no catch-up");
        }
    }
}
