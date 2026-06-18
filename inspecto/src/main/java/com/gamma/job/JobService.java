package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
import com.gamma.flow.DeletionFence;
import com.gamma.flow.FlowStore;
import com.gamma.flow.exec.FlowJobRunner;
import com.gamma.metrics.MetricRegistry;
import com.gamma.report.ReportService;
import com.gamma.service.BatchEventBus;
import com.gamma.service.CronExpression;
import com.gamma.service.Scheduler;
import com.gamma.util.BoundedHistory;
import com.gamma.util.CsvLedger;
import com.gamma.util.LockingRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry + scheduler for config-driven {@link Job}s (v2.8.0). It hosts a uniform "what
 * runs when" layer over the platform's engines: each {@link JobConfig} becomes a {@link Job}
 * (ingest / enrich / report / maintenance) that the service schedules by {@link CronExpression},
 * fires on an upstream batch-commit event, or runs on demand — recording every execution to a
 * durable audit and a short in-memory history the Control API serves.
 *
 * <h3>Triggers</h3>
 * <ul>
 *   <li><b>Cron</b> — jobs with a {@code cron} field are armed on the borrowed {@link Scheduler}.</li>
 *   <li><b>Event</b> — jobs with {@code on_pipeline} run when that pipeline (or upstream job)
 *       commits a batch, via the shared {@link BatchEventBus}.</li>
 *   <li><b>Manual</b> — {@link #trigger(String)} runs a job once (Control API {@code POST}).</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * The cron/event triggers only <em>submit</em> work to an internal virtual-thread executor, so
 * the scheduler thread is never blocked by a long job. A per-job lock guarantees non-overlap:
 * if a job fires while its previous run is still in flight, the new fire records {@code SKIPPED}
 * rather than running concurrently. Different jobs run in parallel.
 *
 * <p>The {@link Scheduler} is <b>borrowed</b> (owned by the hosting service); {@link #close()}
 * shuts down only the executor this service created.
 */
@PublicApi(since = "2.8.0")
public final class JobService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final DateTimeFormatter TS     = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RUN_TS  = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int MAX_HISTORY = 50;

    private final List<JobConfig> configs;
    private final BatchEventBus bus;
    private final Scheduler scheduler;
    private final ReportService reports;
    private final ZoneId zone;
    /** Append-only run audit ({@code jobs_runs.csv}) — a job run isn't a recoverable unit,
     *  so a single durable CSV is the right grain (no commit log). */
    private final CsvLedger<JobRun> audit;
    /** The audit file path — read back at startup for misfire/catch-up (T26); the CsvLedger is write-only. */
    private final Path auditFile;
    /** The audit dir (parent of {@link #auditFile}) — also where a {@code flow} job's branch-commit log lives (T32). */
    private final String auditDir;
    /** Optional DuckDB projection of job runs for reporting (T27); {@code null} when no backend is configured. */
    private final DbJobRunStore jobRunStore;
    /** Authored-flow store for {@link JobType#FLOW} jobs (T32); {@code null} when no write root is configured. */
    private final FlowStore flowStore;
    /** Data root under which each store is a sub-directory — a flow job reads/writes {@code <dataDir>/<store>} (T32). */
    private final String dataDir;
    /** Optional deletion fence (T25): consulted before a {@code maintenance} job that declares a {@code store:}
     *  deletes, to surface a conflict when the delete races an active reader/writer. {@code null} = no fence. */
    private volatile DeletionFence.Guard deletionGuard;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, CronExpression> crons = new ConcurrentHashMap<>();
    private final LockingRunner runner = new LockingRunner();
    private final Map<String, BoundedHistory<JobRun>> history = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    /** Flow ids (authored-flow graph names) of {@link JobType#FLOW} jobs currently in flight — fed to the
     *  deletion fence (T32) so a delete that races an active flow-job reader/writer surfaces a conflict. */
    private final Set<String> runningFlows = ConcurrentHashMap.newKeySet();

    /** A job's identity + state for the Control API listing. */
    public record JobView(String name, String type, String cron, String onPipeline,
                          boolean enabled, String lastStatus, String lastRunTime, String nextFire) {}

    public JobService(List<JobConfig> configs, BatchEventBus bus, Scheduler scheduler,
                      ReportService reports, String auditDir) {
        this(configs, bus, scheduler, reports, auditDir, null);
    }

    /** As above, plus an optional DuckDB job-run projection for reporting (T27); {@code null} disables it. */
    public JobService(List<JobConfig> configs, BatchEventBus bus, Scheduler scheduler,
                      ReportService reports, String auditDir, DbJobRunStore jobRunStore) {
        this(configs, bus, scheduler, reports, auditDir, jobRunStore, null, "database");
    }

    /**
     * Full constructor. Adds the authored-flow store and data root that {@link JobType#FLOW} jobs need (T32):
     * a flow job loads its flow from {@code flowStore} and reads/writes stores under {@code dataDir}. Both may
     * be left at {@code null}/default when no flow jobs are configured.
     */
    public JobService(List<JobConfig> configs, BatchEventBus bus, Scheduler scheduler,
                      ReportService reports, String auditDir, DbJobRunStore jobRunStore,
                      FlowStore flowStore, String dataDir) {
        this.configs   = List.copyOf(configs);
        this.bus       = bus;
        this.scheduler = scheduler;
        this.reports   = reports;
        this.zone      = ZoneId.systemDefault();
        this.audit     = openAudit(auditDir);
        this.auditFile = Paths.get(auditDir).resolve("jobs_runs.csv");
        this.auditDir  = auditDir;
        this.jobRunStore = jobRunStore;
        this.flowStore = flowStore;
        this.dataDir   = dataDir;
        for (JobConfig c : this.configs) {
            if (c.enabled()) jobs.put(c.name(), build(c));
        }
    }

    /** Wire the event subscriber and arm cron schedules. */
    public void start() {
        bus.subscribe(this::onBatchEvent);
        int cronCount = 0, eventCount = 0;
        for (JobConfig c : configs) {
            if (!c.enabled()) continue;
            if (c.hasCron()) {
                CronExpression expr = c.cronExpression();
                crons.put(c.name(), expr);
                scheduler.cron("job-" + c.name(), expr, zone, () -> submit(c.name(), "schedule"));
                cronCount++;
            }
            if (c.hasEvent()) eventCount++;
        }
        log.info("JobService started: {} job(s) — {} cron-scheduled, {} event-triggered",
                jobs.size(), cronCount, eventCount);
        catchUpMissedFires();
    }

    /**
     * Misfire / catch-up (T26, §3.8 — the one real Quartz gap, done without Quartz). On startup, for each
     * enabled {@code catch_up: true} cron job, read its last run from the durable {@code jobs_runs.csv}
     * audit and, if the cron schedule had a fire <em>between that last run and now</em> (i.e. one was missed
     * while the service was down), submit a single immediate run. A job that has never run has no baseline,
     * so it is left to its normal next-fire arming (this avoids firing every catch-up job on a fresh deploy).
     */
    private void catchUpMissedFires() {
        Map<String, LocalDateTime> lastStart = lastStartTimesFromAudit();
        int caughtUp = 0;
        ZonedDateTime now = ZonedDateTime.now(zone);
        for (JobConfig c : configs) {
            if (!c.enabled() || !c.hasCron() || !c.catchUp()) continue;
            LocalDateTime last = lastStart.get(c.name());
            if (last == null) continue;                                   // never run — nothing to catch up
            ZonedDateTime nextAfterLast = c.cronExpression().next(last.atZone(zone));
            if (!nextAfterLast.isAfter(now)) {                            // a scheduled fire elapsed while down
                log.info("[JOB] {} catch-up: a scheduled fire was missed since {} — running once now",
                        c.name(), last);
                submit(c.name(), "catch-up");
                caughtUp++;
            }
        }
        if (caughtUp > 0) log.info("JobService catch-up: {} job(s) had a missed fire", caughtUp);
    }

    /** Latest {@code start_time} per job from the audit CSV (empty when absent) — the catch-up baseline. */
    private Map<String, LocalDateTime> lastStartTimesFromAudit() {
        Map<String, LocalDateTime> out = new LinkedHashMap<>();
        if (auditFile == null || !Files.exists(auditFile)) return out;
        try {
            List<String> lines = Files.readAllLines(auditFile);
            for (int i = 1; i < lines.size(); i++) {                      // row 0 is the header
                String[] f = lines.get(i).split(",", -1);                 // job + start_time are comma-free fields
                if (f.length < 5) continue;
                try {
                    LocalDateTime start = LocalDateTime.parse(f[4], TS);
                    out.merge(f[1], start, (a, b) -> b.isAfter(a) ? b : a);
                } catch (RuntimeException ignore) { /* skip a malformed row */ }
            }
        } catch (IOException e) {
            log.warn("Could not read job audit for catch-up ({}): {}", auditFile, e.getMessage());
        }
        return out;
    }

    private Job build(JobConfig c) {
        return switch (c.type()) {
            case ENRICH      -> new EnrichJob(c, bus);
            case REPORT      -> new ReportJob(c, reports);
            case MAINTENANCE -> new MaintenanceJob(c);
            case FLOW        -> buildFlowJob(c);
        };
    }

    /** A {@link JobType#FLOW} job (T32) — requires an authored-flow store (set {@code -Dassist.write.root}). */
    private Job buildFlowJob(JobConfig c) {
        if (flowStore == null)
            throw new IllegalStateException("flow job '" + c.name()
                    + "' needs an authored-flow store; set -Dassist.write.root so authored flows can be loaded");
        return new FlowJobRunner(c, bus, flowStore, dataDir, auditDir);
    }

    private void onBatchEvent(BatchEvent event) {
        if (!"SUCCESS".equals(event.status())) return;
        for (JobConfig c : configs) {
            if (!c.enabled() || !c.hasEvent()) continue;
            if (!c.onPipeline().equals(event.pipeline())) continue;
            if (c.name().equals(event.pipeline())) continue;   // self-loop guard
            submit(c.name(), "event:" + event.pipeline());
        }
    }

    /** Run a job once by name, off the caller's thread. Returns false if no such (enabled) job. */
    public boolean trigger(String name) {
        if (!jobs.containsKey(name)) return false;
        submit(name, "manual");
        return true;
    }

    private void submit(String name, String trigger) {
        workers.submit(() -> runJob(name, trigger));
    }

    private void runJob(String name, String trigger) {
        Job job = jobs.get(name);
        if (job == null) return;
        String runId = name.toLowerCase().replace(' ', '_') + "-"
                + LocalDateTime.now().format(RUN_TS) + "-" + seq.incrementAndGet();
        String start = LocalDateTime.now().format(TS);
        runner.runExclusiveOrSkip(name, () -> {
            fenceDelete(name);   // T25: surface a conflict if a declared delete races an active reader/writer
            String flowId = trackFlowStart(job, name);   // T32: mark a flow job's stores active for the fence
            JobResult res;
            try {
                res = job.run();
            } catch (Exception e) {
                log.error("Job '{}' ({}) failed", name, trigger, e);
                res = JobResult.failed(String.valueOf(e.getMessage()),
                        0L);
            } finally {
                if (flowId != null) runningFlows.remove(flowId);
            }
            JobRun run = new JobRun(runId, name, job.type().name(), trigger, start,
                    LocalDateTime.now().format(TS), res.status(), res.durationMs(), res.message());
            recordRun(run);
            MetricRegistry.global().inc("inspecto_jobs_total", "Config-driven job executions",
                    Map.of("job", name, "type", job.type().name(), "status", res.status()));
            MetricRegistry.global().observe("inspecto_job_duration_seconds", "Job wall time",
                    Map.of("job", name), res.durationMs() / 1000.0);
            log.info("[JOB] {} ({}) {} in {}ms — {}",
                    name, trigger, res.status(), res.durationMs(), res.message());
        }, () ->   // a previous run is still in flight — don't overlap
            recordRun(new JobRun(runId, name, job.type().name(), trigger, start,
                    LocalDateTime.now().format(TS), "SKIPPED", 0L, "previous run still in flight")));
    }

    /** Create the audit dir and open the {@code jobs_runs.csv} ledger (was JobAuditWriter). */
    private static CsvLedger<JobRun> openAudit(String auditDir) {
        Path dir = Paths.get(auditDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create jobs audit dir: " + auditDir, e);
        }
        return new CsvLedger<>(dir.resolve("jobs_runs.csv").toString(),
                "run_id,job,type,trigger,start_time,end_time,status,duration_ms,message",
                r -> String.format("%s,%s,%s,%s,%s,%s,%s,%d,\"%s\"",
                        r.runId(), r.job(), r.type(), r.trigger(), r.startTime(), r.endTime(),
                        r.status(), r.durationMs(), CsvLedger.q(r.message())));
    }

    private void recordRun(JobRun run) {
        try {
            audit.append(run);
        } catch (Exception e) {
            log.warn("Could not write job audit for {}: {}", run.job(), e.getMessage());
        }
        history.computeIfAbsent(run.job(), k -> new BoundedHistory<>(MAX_HISTORY)).add(run);
        if (jobRunStore != null) jobRunStore.record(run);   // T27: durable, queryable projection
    }

    /** The DuckDB job-run projection for reporting (T27), or empty when no backend is configured. */
    public Optional<DbJobRunStore> runStore() {
        return Optional.ofNullable(jobRunStore);
    }

    /** Install the deletion fence (T25) consulted before a delete job declaring a {@code store:} runs. */
    public void deletionGuard(DeletionFence.Guard guard) {
        this.deletionGuard = guard;
    }

    /** Consult the fence for a {@code maintenance} job that declares the store(s) it deletes (T25). */
    private void fenceDelete(String name) {
        DeletionFence.Guard guard = deletionGuard;
        if (guard == null) return;
        JobConfig jc = configFor(name).orElse(null);
        if (jc == null || jc.type() != JobType.MAINTENANCE) return;
        String storeCsv = jc.opt("store", "").trim();
        if (storeCsv.isBlank()) return;
        List<String> stores = java.util.Arrays.stream(storeCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!stores.isEmpty()) guard.check(stores);   // the guard logs the warning + emits the conflict event
    }

    /**
     * Mark a {@link JobType#FLOW} job's flow as running for the deletion fence (T32) and return its flow id;
     * {@code null} for a non-flow job. The id is the authored-flow graph name (the job's {@code flow} param),
     * so it matches the flow names {@link DeletionFence#check} derives from the authored flows the live
     * {@code SourceService} feeds it — a delete racing this flow's store then surfaces as a conflict.
     */
    private String trackFlowStart(Job job, String name) {
        if (job.type() != JobType.FLOW) return null;
        String flowId = configFor(name).map(c -> c.opt("flow", name)).orElse(name);
        runningFlows.add(flowId);
        return flowId;
    }

    /** The configured job by name (the run path keys by name; configs is the source of truth for params). */
    private Optional<JobConfig> configFor(String name) {
        return configs.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /**
     * Flow ids of {@link JobType#FLOW} jobs currently in flight. The live {@code SourceService} unions this
     * into the deletion fence's running-set (T32) so deleting a store an active flow job reads/writes is
     * flagged as a {@code STORE_DELETE_CONFLICT}, just as for a running pipeline.
     */
    public Set<String> runningFlows() {
        return Set.copyOf(runningFlows);
    }

    // ── Control API surface ──────────────────────────────────────────────────────

    /** List every configured job with its schedule, last outcome and next fire time. */
    public List<JobView> jobs() {
        List<JobView> out = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(zone);
        for (JobConfig c : configs) {
            JobRun last = lastRun(c.name());
            String nextFire = "";
            if (c.enabled() && c.hasCron()) {
                CronExpression expr = crons.getOrDefault(c.name(), c.cronExpression());
                nextFire = expr.next(now).format(TS);
            }
            out.add(new JobView(c.name(), c.type().name(), c.cron(), c.onPipeline(), c.enabled(),
                    last == null ? "" : last.status(),
                    last == null ? "" : last.endTime(),
                    nextFire));
        }
        return out;
    }

    /** Recent run history (newest first) for one job; empty if unknown or never run. */
    public List<JobRun> runsFor(String name) {
        BoundedHistory<JobRun> hist = history.get(name);
        return hist == null ? List.of() : hist.all();
    }

    /** The most recent run of a job, if any. */
    public Optional<JobRun> lastRunOf(String name) {
        return Optional.ofNullable(lastRun(name));
    }

    private JobRun lastRun(String name) {
        BoundedHistory<JobRun> hist = history.get(name);
        return hist == null ? null : hist.latest().orElse(null);
    }

    /** Whether any job by this name is registered and enabled. */
    public boolean has(String name) { return jobs.containsKey(name); }

    @Override
    public void close() {
        workers.close();   // virtual-thread executor: awaits in-flight job runs
        if (jobRunStore != null) jobRunStore.close();
        log.info("JobService stopped");
    }
}
