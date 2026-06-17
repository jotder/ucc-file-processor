package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
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

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Map<String, CronExpression> crons = new ConcurrentHashMap<>();
    private final LockingRunner runner = new LockingRunner();
    private final Map<String, BoundedHistory<JobRun>> history = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** A job's identity + state for the Control API listing. */
    public record JobView(String name, String type, String cron, String onPipeline,
                          boolean enabled, String lastStatus, String lastRunTime, String nextFire) {}

    public JobService(List<JobConfig> configs, BatchEventBus bus, Scheduler scheduler,
                      ReportService reports, String auditDir) {
        this.configs   = List.copyOf(configs);
        this.bus       = bus;
        this.scheduler = scheduler;
        this.reports   = reports;
        this.zone      = ZoneId.systemDefault();
        this.audit     = openAudit(auditDir);
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
    }

    private Job build(JobConfig c) {
        return switch (c.type()) {
            case ENRICH      -> new EnrichJob(c, bus);
            case REPORT      -> new ReportJob(c, reports);
            case MAINTENANCE -> new MaintenanceJob(c);
        };
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
            JobResult res;
            try {
                res = job.run();
            } catch (Exception e) {
                log.error("Job '{}' ({}) failed", name, trigger, e);
                res = JobResult.failed(String.valueOf(e.getMessage()),
                        0L);
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
        log.info("JobService stopped");
    }
}
