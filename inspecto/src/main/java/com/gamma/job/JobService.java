package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
import com.gamma.pipeline.DeletionFence;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.exec.PipelineJobRunner;
import com.gamma.event.EventLog;
import com.gamma.metrics.MetricRegistry;
import com.gamma.report.ReportService;
import com.gamma.service.BatchEventBus;
import com.gamma.service.CronExpression;
import com.gamma.service.Scheduler;
import com.gamma.util.LockingRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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

    private final List<JobConfig> configs;
    private final BatchEventBus bus;
    private final Scheduler scheduler;
    private final ReportService reports;
    private final ZoneId zone;
    /** Run journal (T26/T27): the durable {@code jobs_runs.csv} audit, the in-memory history the Control
     *  API serves, and the optional DuckDB run projection. */
    private final JobRunLedger ledger;
    /** The audit dir — also where a {@code flow} job's branch-commit log lives (T32). */
    private final String auditDir;
    /** Optional DuckDB data-plane provenance store for FLOW jobs (T21); {@code null} when no backend is configured. */
    private final com.gamma.pipeline.exec.DbProvenanceStore provenanceStore;
    /** Authored-flow store for {@link JobType#FLOW} jobs (T32); {@code null} when no write root is configured. */
    private final PipelineStore flowStore;
    /** Data root under which each store is a sub-directory — a flow job reads/writes {@code <dataDir>/<store>} (T32). */
    private final String dataDir;
    /** Optional deletion fence (T25): consulted before a {@code maintenance} job that declares a {@code store:}
     *  deletes, to surface a conflict when the delete races an active reader/writer. {@code null} = no fence. */
    private volatile DeletionFence.Guard deletionGuard;
    /** The space this service's jobs belong to; each job run executes under this MDC so the per-space EventLog /
     *  metric label / acquisition routing resolves correctly. Defaults to the default space (single-space identical). */
    private volatile String spaceId = EventLog.DEFAULT_SPACE_ID;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, CronExpression> crons = new ConcurrentHashMap<>();
    private final LockingRunner runner = new LockingRunner();
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

    /** As the full constructor, with no data-plane provenance store (T21). */
    public JobService(List<JobConfig> configs, BatchEventBus bus, Scheduler scheduler,
                      ReportService reports, String auditDir, DbJobRunStore jobRunStore,
                      PipelineStore flowStore, String dataDir) {
        this(configs, bus, scheduler, reports, auditDir, jobRunStore, flowStore, dataDir, null);
    }

    /**
     * Full constructor. Adds the authored-flow store and data root that {@link JobType#FLOW} jobs need (T32):
     * a flow job loads its flow from {@code flowStore} and reads/writes stores under {@code dataDir}; plus an
     * optional {@code provenanceStore} (T21) it records per-edge record counts to. All may be left at
     * {@code null}/default when no flow jobs / no provenance backend are configured.
     */
    public JobService(List<JobConfig> configs, BatchEventBus bus, Scheduler scheduler,
                      ReportService reports, String auditDir, DbJobRunStore jobRunStore,
                      PipelineStore flowStore, String dataDir,
                      com.gamma.pipeline.exec.DbProvenanceStore provenanceStore) {
        this.configs   = List.copyOf(configs);
        this.bus       = bus;
        this.scheduler = scheduler;
        this.reports   = reports;
        this.zone      = ZoneId.systemDefault();
        this.auditDir  = auditDir;
        this.ledger    = new JobRunLedger(auditDir, jobRunStore);
        this.flowStore = flowStore;
        this.dataDir   = dataDir;
        this.provenanceStore = provenanceStore;
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
        Map<String, LocalDateTime> lastStart = ledger.lastStartTimes();
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
        return new PipelineJobRunner(c, bus, flowStore, dataDir, auditDir, provenanceStore);
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
        return trigger(name, null);
    }

    /**
     * Run a job once by name, attributing the manual fire to {@code actor} (an operator id / channel) when given
     * (T32 Phase C). The recorded trigger becomes {@code manual:<actor>}; cron / event / catch-up self-attribute
     * ({@code schedule}, {@code event:<pipeline>}, {@code catch-up}). Returns false if no such (enabled) job.
     */
    public boolean trigger(String name, String actor) {
        if (!jobs.containsKey(name)) return false;
        submit(name, actor == null || actor.isBlank() ? "manual" : "manual:" + actor.trim());
        return true;
    }

    private void submit(String name, String trigger) {
        // The default space runs with no MDC (it is the fallback namespace everywhere); a named space sets it so
        // this job run's events/metrics/acquisition route to that space. Fresh per-task virtual thread → clear after.
        boolean scoped = !EventLog.DEFAULT_SPACE_ID.equals(spaceId);
        workers.submit(() -> {
            if (scoped) MDC.put(EventLog.SPACE_MDC_KEY, spaceId);
            try {
                runJob(name, trigger);
            } finally {
                if (scoped) MDC.remove(EventLog.SPACE_MDC_KEY);
            }
        });
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
            ledger.record(run);
            MetricRegistry.global().inc("inspecto_jobs_total", "Config-driven job executions",
                    Map.of("job", name, "type", job.type().name(), "status", res.status()));
            MetricRegistry.global().observe("inspecto_job_duration_seconds", "Job wall time",
                    Map.of("job", name), res.durationMs() / 1000.0);
            log.info("[JOB] {} ({}) {} in {}ms — {}",
                    name, trigger, res.status(), res.durationMs(), res.message());
        }, () ->   // a previous run is still in flight — don't overlap
            ledger.record(new JobRun(runId, name, job.type().name(), trigger, start,
                    LocalDateTime.now().format(TS), "SKIPPED", 0L, "previous run still in flight")));
    }

    /** The DuckDB job-run projection for reporting (T27), or empty when no backend is configured. */
    public Optional<DbJobRunStore> runStore() {
        return ledger.runStore();
    }

    /** The DuckDB data-plane provenance store (T21), or empty when no backend is configured. */
    public Optional<com.gamma.pipeline.exec.DbProvenanceStore> provenanceStore() {
        return Optional.ofNullable(provenanceStore);
    }

    /** Install the deletion fence (T25) consulted before a delete job declaring a {@code store:} runs. */
    public void deletionGuard(DeletionFence.Guard guard) {
        this.deletionGuard = guard;
    }

    /** Bind this service's jobs to a space so each run executes under its MDC (per-space routing). */
    public void spaceId(String spaceId) {
        if (spaceId != null && !spaceId.isBlank()) this.spaceId = spaceId;
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
            JobRun last = ledger.lastRun(c.name());
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
        return ledger.runsFor(name);
    }

    /** The most recent run of a job, if any. */
    public Optional<JobRun> lastRunOf(String name) {
        return Optional.ofNullable(ledger.lastRun(name));
    }

    /** Whether any job by this name is registered and enabled. */
    public boolean has(String name) { return jobs.containsKey(name); }

    @Override
    public void close() {
        workers.close();   // virtual-thread executor: awaits in-flight job runs
        ledger.close();
        if (provenanceStore != null) provenanceStore.close();
        log.info("JobService stopped");
    }
}
