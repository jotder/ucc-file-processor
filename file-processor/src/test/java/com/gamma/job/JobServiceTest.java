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
        return new JobConfig(name, JobType.MAINTENANCE, cron, onPipeline, true, params);
    }

    /** Poll until a run for {@code name} appears (or fail after 6s). */
    private static JobRun await(Supplier<JobRun> s) throws Exception {
        // 20s, not a tight window: these assert a scheduler/event eventually fires. On a loaded
        // CI runner the daemon scheduler threads can be starved for several seconds (the heavy
        // DuckDB suite runs alongside), so a small timeout flakes without indicating a real fault.
        long deadline = System.nanoTime() + 20_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 20s");
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
            JobRun run = await(() -> js.lastRunOf("tick").orElse(null));
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
        JobConfig off = new JobConfig("off", JobType.MAINTENANCE, "* * * * * *", null, false,
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
}
