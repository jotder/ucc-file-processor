package com.gamma.job;

import com.gamma.etl.BatchEvent;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStore;
import com.gamma.etl.BatchEventBus;
import com.gamma.util.Scheduler;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
            assertEquals("maintenance", run.type());
            assertEquals(1, js.runsFor("hb").size(), "one run in history");
            assertTrue(Files.exists(auditDir.resolve("jobs_runs.csv")), "durable audit written");
            assertTrue(Files.readString(auditDir.resolve("jobs_runs.csv")).contains("hb"));
        }
    }

    @Test
    void manualTriggerAttributesTheActor(@TempDir Path dir) throws Exception {
        // T32 Phase C — an operator/channel passed to trigger(name, actor) is recorded as 'manual:<actor>'.
        JobConfig hb = maintenance("hb", null, null, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new BatchEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();
            assertTrue(js.trigger("hb", "alice"));
            JobRun run = await(() -> js.lastRunOf("hb").orElse(null));
            assertEquals("manual:alice", run.trigger(), "the operator is attributed in the run audit");
        }
    }

    @Test
    void triggerRunSurfacesRunIdAndPollByIdTracksLifecycle(@TempDir Path dir) throws Exception {
        // W5: triggerRun returns the runId synchronously; runById polls it RUNNING → terminal.
        JobConfig hb = maintenance("hb", null, null, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new BatchEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();
            var runId = js.triggerRun("hb", null);
            assertTrue(runId.isPresent(), "a known job returns its runId");
            assertTrue(js.runById(runId.get()).isPresent(), "pollable immediately (RUNNING or already done)");
            JobRun done = await(() -> {
                JobRun r = js.runById(runId.get()).orElse(null);
                return r != null && !"RUNNING".equals(r.status()) ? r : null;
            });
            assertEquals("SUCCESS", done.status());
            assertEquals(runId.get(), done.runId());
            assertNotNull(done.endTime(), "a finished run has an end time");
        }
    }

    @Test
    void triggerRunAndPollAreEmptyForUnknown(@TempDir Path dir) throws Exception {
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();
            assertTrue(js.triggerRun("ghost", null).isEmpty(), "no such job → no runId");
            assertTrue(js.runById("nope-1").isEmpty(), "unknown runId → empty");
        }
    }

    @Test
    void missingRequiredParameterRejectsBeforeRunning(@TempDir Path dir) throws Exception {
        // P3a (§7.2): the 'enrich' Job Type declares a required 'config' parameter. Authoring an enrich
        // job without it fails the run REJECTED before EnrichJob executes (fail-closed), rather than
        // throwing inside the job — the resolver gates the run path for every Job Type.
        JobConfig noConfig = new JobConfig("needs_config", JobType.ENRICH, null, null, true, false, Map.of());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(noConfig), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            assertTrue(js.trigger("needs_config"));
            JobRun run = await(() -> js.lastRunOf("needs_config").orElse(null));
            assertEquals("REJECTED", run.status());
            assertTrue(run.message().contains("config"), "names the missing parameter: " + run.message());
        }
    }

    @Test
    void triggerArgsSatisfyARequiredParameterAndClearTheReject(@TempDir Path dir) throws Exception {
        // P3a-2 (§7.2 layer 1): the same enrich job that REJECTs with no 'config' passes the resolver gate
        // when the manual trigger supplies it as an explicit arg — proving trigger args reach the run path.
        JobConfig noConfig = new JobConfig("needs_config", JobType.ENRICH, null, null, true, false, Map.of());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(noConfig), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            assertTrue(js.triggerRun("needs_config", null,
                    Map.of("config", dir.resolve("missing.toon").toString())).isPresent());
            JobRun run = await(() -> js.lastRunOf("needs_config").orElse(null));
            assertNotEquals("REJECTED", run.status(),
                    "an explicit trigger arg satisfies the required parameter, so the run is not rejected");
        }
    }

    @Test
    void unloadedPackFlipsItsJobUnavailableAndRejectsALaterRun(@TempDir Path dir) throws Exception {
        // Job Pack unload-quiesce follow-up: a Job instance already built from a pack's classloader must
        // be flipped unavailable when that pack unloads, so a *later* Run on the same config is rejected
        // (fail-closed) instead of silently running the stale cached Job.
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(dir.resolve("packs"));
        Path jar = buildPackJar(dir, packsDir.resolve("greet-1.jar"), "acme.greet", "GreetType", "acme-greet");
        System.setProperty("jobs.packs.dir", packsDir.toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString())) {
            js.start();
            js.upsertJob(new JobConfig("g1", "acme.greet", null, null, true, false, Map.of(), null, null));

            var runId1 = js.triggerRun("g1", null);
            assertTrue(runId1.isPresent(), "job authored against the pack type triggers");
            JobRun first = await(() -> {
                JobRun r = js.runById(runId1.get()).orElse(null);
                return r != null && !"RUNNING".equals(r.status()) ? r : null;
            });
            assertEquals("SUCCESS", first.status(), "runs normally while the pack is loaded");

            // Remove the jar and reconcile → the pack unloads and deregisters its type.
            Files.delete(jar);
            Map<String, Object> summary = js.rescanPacks();
            assertEquals(List.of("greet-1.jar"), summary.get("unloaded"));

            var runId2 = js.triggerRun("g1", null);
            assertTrue(runId2.isPresent(), "the job is still registered — trigger accepts, but the Run rejects");
            JobRun second = await(() -> {
                JobRun r = js.runById(runId2.get()).orElse(null);
                return r != null && !"RUNNING".equals(r.status()) ? r : null;
            });
            assertEquals("REJECTED", second.status(),
                    "the stale cached Job instance must not run once its pack is unloaded");
            assertTrue(second.message() != null && second.message().contains("unavailable"),
                    "names why: " + second.message());
        } finally {
            System.clearProperty("jobs.packs.dir");
        }
    }

    // ── concurrency bound (-Djobs.maxConcurrentRuns) ──────────────────────────────────

    @Test
    void concurrencyBoundIsUnboundedByDefault(@TempDir Path dir) throws Exception {
        JobConfig hb = maintenance("hb", null, null, Map.of("task", "heartbeat"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(hb), new BatchEventBus(), s, null, dir.resolve("audit").toString())) {
            assertEquals(-1, js.availableRunPermits(), "no -Djobs.maxConcurrentRuns set → unbounded");
        }
    }

    @Test
    void concurrencyBoundSerializesRunsAcrossJobs(@TempDir Path dir) throws Exception {
        // With a bound of 1, two different jobs fired at once cannot run concurrently: the second's
        // permit acquire (on its worker thread, not the caller) waits until the first releases.
        assumeTrue(ToolProvider.getSystemJavaCompiler() != null, "needs a JDK (javac) to build the pack jar");
        Path packsDir = Files.createDirectories(dir.resolve("packs"));
        buildSleepPackJar(dir, packsDir.resolve("sleep-1.jar"), "acme.sleep", "SleepType", "acme-sleep");
        System.setProperty("jobs.packs.dir", packsDir.toString());
        System.setProperty("jobs.maxConcurrentRuns", "1");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();
            assertEquals(1, js.availableRunPermits(), "bound of 1 → one free permit while idle");
            js.upsertJob(new JobConfig("s1", "acme.sleep", null, null, true, false, Map.of(), null, null));
            js.upsertJob(new JobConfig("s2", "acme.sleep", null, null, true, false, Map.of(), null, null));

            js.triggerRun("s1", null);
            js.triggerRun("s2", null);

            // One run holds the sole permit; the other is queued (0 permits free) and not yet SUCCESS.
            JobRun firstDone = await(() -> {
                JobRun a = js.lastRunOf("s1").filter(r -> "SUCCESS".equals(r.status())).orElse(null);
                JobRun b = js.lastRunOf("s2").filter(r -> "SUCCESS".equals(r.status())).orElse(null);
                return a != null ? a : b;
            });
            assertNotNull(firstDone);
            // both eventually complete once the permit recycles
            JobRun s1 = await(() -> js.lastRunOf("s1").filter(r -> "SUCCESS".equals(r.status())).orElse(null));
            JobRun s2 = await(() -> js.lastRunOf("s2").filter(r -> "SUCCESS".equals(r.status())).orElse(null));
            assertEquals("SUCCESS", s1.status());
            assertEquals("SUCCESS", s2.status());
            // The permit is released in a finally on the worker thread, which can lag a few nanos behind
            // the SUCCESS status flip above — so wait for it to recycle rather than reading it synchronously.
            assertNotNull(await(() -> js.availableRunPermits() == 1 ? s1 : null),
                "permit released after both runs finish");
        } finally {
            System.clearProperty("jobs.packs.dir");
            System.clearProperty("jobs.maxConcurrentRuns");
        }
    }

    /** A pack whose Job body sleeps, so the concurrency bound has an observable window to serialize within. */
    private static Path buildSleepPackJar(Path work, Path jar, String id, String cls, String packId) throws Exception {
        return buildPackJar(work, jar, id, cls, packId, "try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return JobResult.ok(\"slept\", 400L);");
    }

    /** Compile+jar a minimal {@link JobTypeProvider}/{@link Job} pair off the test classpath, mirroring the
     *  fixture in {@code JobPackManagerTest} (kept local here so this test doesn't reach across test classes). */
    private static Path buildPackJar(Path work, Path jar, String id, String cls, String packId) throws Exception {
        return buildPackJar(work, jar, id, cls, packId, "return JobResult.ok(\"hi\", 0L);");
    }

    private static Path buildPackJar(Path work, Path jar, String id, String cls, String packId, String runBody) throws Exception {
        String fqcn = "com.acme.pack." + cls;
        String src = """
                package com.acme.pack;
                import com.gamma.job.*;
                import java.util.List;
                @JobTypeMeta(id = "%s", title = "Test")
                public class %s implements JobTypeProvider {
                    public JobTypeDescriptor descriptor() {
                        return new JobTypeDescriptor("%s", "Test", "test pack type",
                                List.of(), List.of(), List.of());
                    }
                    public Job create(JobConfig config) {
                        return new Job() {
                            public String name() { return config.name(); }
                            public String type() { return "%s"; }
                            public JobResult run() { %s }
                        };
                    }
                }
                """.formatted(id, cls, id, id, runBody);

        Path stage = Files.createTempDirectory(work, "stage-");
        Path srcFile = stage.resolve("com/acme/pack/" + cls + ".java");
        Files.createDirectories(srcFile.getParent());
        Files.writeString(srcFile, src);
        Path classes = Files.createDirectories(stage.resolve("classes"));

        String apiCp = Path.of(JobTypeProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> opts = List.of("-classpath", apiCp, "-d", classes.toString());
            boolean ok = jc.getTask(null, fm, null, opts, null,
                    fm.getJavaFileObjects(srcFile.toFile())).call();
            assertTrue(ok, "pack source compiled");
        }

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Pack-Id", packId);
        mf.getMainAttributes().putValue("Pack-Version", "1.0.0");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf);
             Stream<Path> files = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                jos.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jos);
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry("META-INF/services/com.gamma.job.JobTypeProvider"));
            writeUtf8(jos, fqcn + "\n");
            jos.closeEntry();
        }
        return jar;
    }

    private static void writeUtf8(OutputStream os, String s) throws Exception {
        os.write(s.getBytes(StandardCharsets.UTF_8));
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

    // ── T32: flow jobs (JobType.PIPELINE) ──────────────────────────────────────────

    @Test
    void flowJobIsBuiltWhenAFlowStoreIsConfigured(@TempDir Path dir) throws Exception {
        JobConfig fj = new JobConfig("fj", JobType.PIPELINE, null, null, true, false, Map.of("flow", "some_flow"));
        com.gamma.pipeline.PipelineStore store = new com.gamma.pipeline.PipelineStore(dir.resolve("flows"));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(fj), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString(), null, store, dir.resolve("data").toString())) {
            js.start();
            assertTrue(js.has("fj"), "a flow job is built when a flow store is configured");
            assertEquals("pipeline", js.jobs().get(0).type(), "the listing reports the PIPELINE type");
        }
    }

    @Test
    void flowJobWithoutAFlowStoreFailsClosed(@TempDir Path dir) throws Exception {
        JobConfig fj = new JobConfig("fj", JobType.PIPELINE, null, null, true, false, Map.of("flow", "some_flow"));
        // the 5-arg constructor leaves the flow store null → building a flow job must fail closed
        try (Scheduler s = new Scheduler()) {
            assertThrows(IllegalStateException.class, () ->
                    new JobService(List.of(fj), new BatchEventBus(), s, null, dir.resolve("audit").toString()));
        }
    }

    @Test
    void flowJobRunsEndToEndAndIsTrackedWhileRunning(@TempDir Path dir) throws Exception {
        // a tiny at-rest source store + an authored flow that filters it into a sink store
        String dataDir = dir.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(dir.resolve("flows"));
        writeRollupFlow(store, "evt_rollup");

        JobConfig fj = new JobConfig("nightly", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        BatchEventBus bus = new BatchEventBus();
        AtomicReference<Set<String>> midRun = new AtomicReference<>();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(fj), bus, s, null,
                     dir.resolve("audit").toString(), null, store, dataDir)) {
            // the bus is synchronous on the publishing (job) thread, so this fires while run() is still in
            // flight → the flow id must already be in runningFlows() at that instant (before the finally removes it)
            bus.subscribe(ev -> { if ("nightly".equals(ev.pipeline())) midRun.set(js.runningFlows()); });
            js.start();
            assertTrue(js.trigger("nightly"), "the flow job is built and triggerable");
            JobRun run = await(() -> js.lastRunOf("nightly").orElse(null));

            assertEquals("SUCCESS", run.status(), run.message());
            assertEquals("pipeline", run.type());
            assertNotNull(midRun.get(), "the flow's chain event fired");
            assertTrue(midRun.get().contains("evt_rollup"), "flow tracked as running mid-run: " + midRun.get());
            assertTrue(js.runningFlows().isEmpty(), "the running-flow set is cleaned up after the run");
        }
    }

    @Test
    void adhocFlowRunGetsTheFullLifecycleWithoutRegisteringAJob(@TempDir Path dir) throws Exception {
        // T32 config-less run (POST /pipelines/authored/{id}/trigger): no *_job.toon, no registry entry —
        // but the exact registered-run lifecycle (fence tracking, ledger, polling, attribution).
        String dataDir = dir.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(dir.resolve("flows"));
        writeRollupFlow(store, "evt_rollup");

        BatchEventBus bus = new BatchEventBus();
        AtomicReference<Set<String>> midRun = new AtomicReference<>();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), bus, s, null,
                     dir.resolve("audit").toString(), null, store, dataDir)) {
            // the ad-hoc run publishes its chain event under the flow id (there is no job name)
            bus.subscribe(ev -> { if ("evt_rollup".equals(ev.pipeline())) midRun.set(js.runningFlows()); });
            js.start();
            String runId = js.triggerFlowRun("evt_rollup", "rahul");
            JobRun run = await(() -> js.runById(runId).filter(r -> !"RUNNING".equals(r.status())).orElse(null));

            assertEquals("SUCCESS", run.status(), run.message());
            assertEquals("pipeline", run.type());
            assertEquals("manual:rahul", run.trigger(), "the ad-hoc fire is actor-attributed");
            assertEquals("evt_rollup", run.job(), "the run is recorded under the flow id");
            assertNotNull(midRun.get(), "the flow's chain event fired");
            assertTrue(midRun.get().contains("evt_rollup"), "ad-hoc run tracked for the deletion fence mid-run");
            assertTrue(js.runningFlows().isEmpty(), "the running-flow set is cleaned up after the run");
            assertTrue(js.jobs().isEmpty(), "an ad-hoc run never registers a job");
            assertEquals(1, js.runsFor("evt_rollup").size(), "history is browsable under the flow id");
        }
    }

    @Test
    void adhocFlowRunWithoutAFlowStoreFailsClosed(@TempDir Path dir) throws Exception {
        // the 5-arg constructor leaves the flow store null → the ad-hoc path must fail closed too
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(), new BatchEventBus(), s, null, dir.resolve("audit").toString())) {
            js.start();
            assertThrows(IllegalStateException.class, () -> js.triggerFlowRun("ghost", null));
        }
    }

    // ── T32 Phase B: a PIPELINE job is a first-class scheduled/chained job ───────────

    @Test
    void cronFiresAFlowJob(@TempDir Path dir) throws Exception {
        // cron arming is type-agnostic in JobService.start(); prove it actually fires a PIPELINE job.
        String dataDir = dir.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(dir.resolve("flows"));
        writeRollupFlow(store, "evt_rollup");
        JobConfig fj = new JobConfig("ticker", JobType.PIPELINE, "* * * * * *", null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(fj), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString(), null, store, dataDir)) {
            js.start();
            // an every-second cron can re-fire while in flight (→ SKIPPED); assert the first SUCCESS.
            JobRun run = await(() -> js.runsFor("ticker").stream()
                    .filter(r -> "SUCCESS".equals(r.status())).findFirst().orElse(null));
            assertEquals("schedule", run.trigger(), "cron fire records the schedule trigger");
            assertEquals("pipeline", run.type());
        }
    }

    @Test
    void onPipelineEventFiresAFlowJob(@TempDir Path dir) throws Exception {
        // chaining INTO a flow: an upstream pipeline commit triggers the flow job (the recommended
        // pattern over cron when the flow reads a store the pipeline writes — avoids a half-written read).
        String dataDir = dir.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(dir.resolve("flows"));
        writeRollupFlow(store, "evt_rollup");
        JobConfig fj = new JobConfig("rollup_job", JobType.PIPELINE, null, "events_etl", true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        BatchEventBus bus = new BatchEventBus();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(fj), bus, s, null,
                     dir.resolve("audit").toString(), null, store, dataDir)) {
            js.start();
            bus.publish(new BatchEvent("events_etl", "b1", "SUCCESS", List.of("p=1"), 1L, 1L, 0));
            JobRun run = await(() -> js.lastRunOf("rollup_job").orElse(null));
            assertEquals("SUCCESS", run.status(), run.message());
            assertEquals("pipeline", run.type());
            assertTrue(run.trigger().startsWith("event:events_etl"), "fired by the upstream commit: " + run.trigger());
            assertTrue(Files.exists(Path.of(dataDir, "rollup")), "the flow job wrote its sink store");
        }
    }

    @Test
    void aFlowJobSuccessChainsADownstreamJob(@TempDir Path dir) throws Exception {
        // chaining OUT of a flow: PipelineJobRunner publishes a BatchEvent(jobName) on success, so a
        // downstream on_pipeline job fires — the flow job is a first-class upstream in the event graph.
        String dataDir = dir.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(dir.resolve("flows"));
        writeRollupFlow(store, "evt_rollup");
        JobConfig flowJob = new JobConfig("rollup_job", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        JobConfig downstream = maintenance("after_rollup", null, "rollup_job", Map.of("task", "heartbeat"));
        BatchEventBus bus = new BatchEventBus();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(flowJob, downstream), bus, s, null,
                     dir.resolve("audit").toString(), null, store, dataDir)) {
            js.start();
            js.trigger("rollup_job");
            JobRun run = await(() -> js.lastRunOf("after_rollup").orElse(null));
            assertEquals("SUCCESS", run.status());
            assertTrue(run.trigger().startsWith("event:rollup_job"), "chained off the flow job: " + run.trigger());
        }
    }

    @Test
    void flowRunsAreProjectedIntoTheReportingStoreAsTypeFlow(@TempDir Path dir) throws Exception {
        // T27 reporting: a PIPELINE run reaches the DuckDB job-run store, typed PIPELINE (jobs-pane reporting).
        String dataDir = dir.resolve("data").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        PipelineStore store = new PipelineStore(dir.resolve("flows"));
        writeRollupFlow(store, "evt_rollup");
        JobConfig fj = new JobConfig("nightly_rollup", JobType.PIPELINE, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        DbJobRunStore runStore = DbJobRunStore.open("jdbc:duckdb:");   // in-memory; closed by js.close()
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(fj), new BatchEventBus(), s, null,
                     dir.resolve("audit").toString(), runStore, store, dataDir)) {
            js.start();
            js.trigger("nightly_rollup");
            await(() -> js.lastRunOf("nightly_rollup").orElse(null));
            assertTrue(((Number) runStore.metrics("nightly_rollup").get("total")).longValue() >= 1,
                    "the flow run was projected into the reporting store");
            List<Map<String, Object>> recent = runStore.recentRuns(10, "nightly_rollup");
            assertFalse(recent.isEmpty(), "the flow run is queryable in the reporting store");
            assertEquals("pipeline", recent.get(0).get("type"), "reported as a PIPELINE run");
        }
    }

    /** Author the canonical {@code events → filter(amt>=100) → sink rollup} flow used by the T32 tests. */
    private static void writeRollupFlow(PipelineStore store, String id) throws Exception {
        store.write(id, new PipelineGraph(id, true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out"))));
    }

    /** Write {@code (id,amt)} VALUES as a Parquet file under {@code <dataDir>/<store>/} (an at-rest source store). */
    private static void seedParquet(String dataDir, String store, String valuesSql) throws Exception {
        Path d = Path.of(dataDir, store);
        Files.createDirectories(d);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,amt)) TO '"
                    + d.resolve("seed.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
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
