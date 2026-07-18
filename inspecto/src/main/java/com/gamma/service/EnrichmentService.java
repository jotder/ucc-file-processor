package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.enrich.EnrichmentAuditReader;
import com.gamma.enrich.EnrichmentAuditWriter;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentEngine;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PipelineConfig;
import com.gamma.metrics.MetricRegistry;
import com.gamma.util.LockingRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Orchestrates Stage-2 {@link EnrichmentEngine} runs against the platform's two
 * triggers — the flagship "incremental + scheduled" model. It is the composition
 * point that binds the pure enrichment engine ({@code com.gamma.enrich}) to the
 * service's {@link BatchEventBus} (freshness) and {@link Scheduler} (completeness).
 *
 * <h3>Triggers</h3>
 * <ul>
 *   <li><b>Event (freshness)</b> — subscribes to the bus. When a batch commits for a
 *       job's {@code triggers.on_pipeline}, the job recomputes <em>only the partitions
 *       that batch wrote</em> ({@link BatchEvent#partitions()} → input filter). This is
 *       the cheap, near-real-time path.</li>
 *   <li><b>Scheduled (completeness)</b> — for jobs with {@code triggers.schedule_seconds},
 *       registers an interval job that recomputes the <em>full</em> window. Idempotent
 *       overwrite reconciles late-arriving data the event path may have missed.</li>
 * </ul>
 *
 * <h3>Chains</h3>
 * After a successful recompute a job publishes its own {@link BatchEvent} (pipeline =
 * the job's {@code name}, partitions = what it wrote) back onto the bus. A downstream
 * job whose {@code on_pipeline} equals that name therefore fires automatically — Stage-2
 * → Stage-2 chains use the very same machinery. A job is never triggered by its own
 * output (guarded), so an accidental self-reference can't loop.
 *
 * <h3>Concurrency &amp; idempotency</h3>
 * Bus listeners run on the publishing (ingest) thread, so event recomputes are handed
 * to an internal virtual-thread executor — heavy DuckDB work never blocks ingest. A
 * per-job lock serialises that job's recomputes, so an event and a scheduled run for the
 * same job can't race on the same output partitions; different jobs still run in parallel.
 * {@code OVERWRITE_OR_IGNORE} writes make any overlap converge to the same result.
 *
 * <p>The {@link Scheduler} is <b>borrowed</b> (owned by the hosting {@link CollectorService});
 * {@link #close()} shuts down only the executor this service created.
 */
@PublicApi(since = "2.3.0")
public final class EnrichmentService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    /** Live, hot-registrable job list — iterated per bus event, mutated only by {@link #register}. */
    private final List<EnrichmentConfig> jobs;
    private final BatchEventBus bus;
    private final Scheduler scheduler;
    /** Live view of the loaded Stage-1 pipelines — resolves by-name references per recompute. */
    private final Supplier<List<PipelineConfig>> pipelines;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final LockingRunner runner = new LockingRunner();
    private final Map<String, EnrichmentAuditWriter> auditWriters = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    /** Job names whose completeness schedule is armed — the scheduler has no cancel-by-name,
     *  so each name is armed at most once and the task resolves its config at fire time. */
    private final Set<String> armedSchedules = ConcurrentHashMap.newKeySet();

    public EnrichmentService(List<EnrichmentConfig> jobs, BatchEventBus bus, Scheduler scheduler) {
        this(jobs, bus, scheduler, List::of);
    }

    /**
     * @param pipelines live view of the loaded Stage-1 pipelines, consulted per recompute so
     *                  by-name references ({@code references.<name>.ref:}) resolve against the
     *                  current registry (hot-reload safe); {@code List::of} disables by-name refs
     */
    public EnrichmentService(List<EnrichmentConfig> jobs, BatchEventBus bus, Scheduler scheduler,
                             Supplier<List<PipelineConfig>> pipelines) {
        this.jobs      = new CopyOnWriteArrayList<>(jobs);
        this.bus       = bus;
        this.scheduler = scheduler;
        this.pipelines = pipelines == null ? List::of : pipelines;
    }

    /** Wire the event subscriber and register scheduled completeness jobs. */
    public void start() {
        bus.subscribe(this::onBatchEvent);
        int events = 0, scheduled = 0;
        for (EnrichmentConfig job : jobs) {
            if (job.triggers().hasEvent()) events++;
            if (armSchedule(job)) scheduled++;
        }
        log.info("EnrichmentService started: {} job(s) — {} event-triggered, {} scheduled",
                jobs.size(), events, scheduled);
    }

    /**
     * Arm a job's completeness schedule once per name. The task resolves the config by name at
     * fire time, so a later {@link #register} replacement is picked up by the existing timer.
     */
    private boolean armSchedule(EnrichmentConfig job) {
        if (!job.triggers().hasSchedule() || !armedSchedules.add(job.name())) return false;
        long s = job.triggers().scheduleSeconds();
        String name = job.name();
        // initialDelay = interval so the timer (not an immediate run) drives this path
        scheduler.everySeconds("enrich-" + name, s, s,
                () -> config(name).ifPresent(j -> recompute(j, null, "schedule")));
        return true;
    }

    /**
     * Hot-register (or replace, keyed by {@code name}) an enrichment job — the
     * {@code POST /enrichment} authoring seam (v5.1.0). Event triggers apply from the next
     * committed batch; a NEW name's schedule is armed now. Two documented limits, both from the
     * scheduler having no cancel-by-name: replacing a job keeps the original schedule
     * <em>interval</em> (the timer re-reads the config, so everything else applies), and a
     * removed-on-disk job keeps running until restart.
     */
    public synchronized void register(EnrichmentConfig job) {
        boolean replaced = jobs.removeIf(j -> j.name().equals(job.name()));
        jobs.add(job);
        armSchedule(job);
        log.info("EnrichmentService {} job '{}' (event={}, schedule={}s)",
                replaced ? "replaced" : "registered", job.name(),
                job.triggers().hasEvent() ? job.triggers().onPipeline() : "-",
                job.triggers().hasSchedule() ? job.triggers().scheduleSeconds() : 0);
    }

    /** Dispatch a committed-batch event to any job listening on its pipeline. */
    private void onBatchEvent(BatchEvent event) {
        if (!"SUCCESS".equals(event.status())) return;   // enrichment acts only on successful commits
        for (EnrichmentConfig job : jobs) {
            EnrichmentConfig.Triggers t = job.triggers();
            if (!t.hasEvent()) continue;
            if (!t.onPipeline().equals(event.pipeline())) continue;
            if (job.name().equals(event.pipeline())) continue;   // self-loop guard
            List<Map<String, String>> filter = toFilter(job, event.partitions());
            // hand off — never block the publishing (ingest) thread on DuckDB work
            workers.submit(() -> recompute(job, filter, "event:" + event.pipeline()));
        }
    }

    /**
     * Run one enrichment recompute (full when {@code filter} is null/empty, else scoped),
     * then announce it on the bus so downstream chained jobs can react. Serialised per job.
     */
    private void recompute(EnrichmentConfig job, List<Map<String, String>> filter, String reason) {
        runner.runExclusive(job.name(), () -> doRecompute(job, filter, reason));
    }

    /** The recompute body, already serialised per job by {@link #recompute}. */
    private void doRecompute(EnrichmentConfig job, List<Map<String, String>> filter, String reason) {
        boolean full = (filter == null || filter.isEmpty());
        int inputParts = full ? 0 : filter.size();
        String scope   = full ? "full" : inputParts + " input partition(s)";
        String trigger = reason.startsWith("event") ? "event"
                : reason.startsWith("cli") ? "cli" : "schedule";
        // run id correlates the audit rows, the durable commit log, and the chain event
        String runId = job.name().toLowerCase().replace(' ', '_')
                + "-" + EnrichmentAuditWriter.runStamp() + "-" + seq.incrementAndGet();
        String startTime = EnrichmentAuditWriter.now();
        long startNanos = System.nanoTime();
        try {
            EnrichmentEngine.Result res = EnrichmentEngine.runResult(job, filter, pipelines.get(),
                    List.of(), runId);
            List<PartitionOutput> outs = res.outputs();
            List<String> parts = outs.stream().map(PartitionOutput::partition).distinct().toList();
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            long bytes = outs.stream().mapToLong(PartitionOutput::bytes).sum();
            log.info("[ENRICH] {} recomputed ({}) → {} partition file(s), {} row(s)",
                    job.name(), reason, outs.size(), res.totalRows());
            // metrics: one recompute, tagged by job + trigger kind (event|schedule|cli)
            MetricRegistry.global().inc("inspecto_enrichment_recomputes_total",
                    "Stage-2 enrichment recomputes", Map.of("job", job.name(), "trigger", trigger));
            MetricRegistry.global().observe("inspecto_enrichment_duration_seconds",
                    "Enrichment recompute wall time", Map.of("job", job.name()),
                    (System.nanoTime() - startNanos) / 1e9);
            // durable run-level audit + lineage
            auditFor(job).record(new EnrichmentAuditWriter.RunRow(
                    runId, job.name(), trigger, reason, scope, inputParts,
                    startTime, EnrichmentAuditWriter.now(), "SUCCESS",
                    parts.size(), outs.size(), res.totalRows(), bytes, durationMs, ""), outs);
            // chain: a successful enrichment is itself a commit downstream jobs can subscribe to
            bus.publish(new BatchEvent(job.name(), runId, "SUCCESS", parts,
                    res.totalRows(), durationMs, 0));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.error("[ENRICH] {} recompute failed ({})", job.name(), reason, e);
            MetricRegistry.global().inc("inspecto_enrichment_failures_total",
                    "Stage-2 enrichment recompute failures", Map.of("job", job.name()));
            try {
                auditFor(job).record(new EnrichmentAuditWriter.RunRow(
                        runId, job.name(), trigger, reason, scope, inputParts,
                        startTime, EnrichmentAuditWriter.now(), "FAILED",
                        0, 0, 0L, 0L, durationMs, String.valueOf(e.getMessage())), List.of());
            } catch (Exception ae) {
                log.warn("[ENRICH] could not write audit for failed run {}: {}", job.name(), ae.getMessage());
            }
        }
    }

    // ── audit read surface (Control API, v2.9.0) ────────────────────────────────

    /** One enrichment job's identity + trigger config + last-run summary, for listings. */
    public record JobView(String name, String onPipeline, long scheduleSeconds,
                          boolean eventTriggered, boolean scheduled, int runCount,
                          String lastStatus, String lastRunTime) {}

    /** The enrichment jobs hosted here (read-only snapshot). */
    public List<EnrichmentConfig> configs() {
        return List.copyOf(jobs);
    }

    /** Look up a hosted enrichment job by its (case-sensitive) name. */
    public Optional<EnrichmentConfig> config(String name) {
        return jobs.stream().filter(j -> j.name().equals(name)).findFirst();
    }

    /** List every hosted job with its trigger config and last-run summary from the audit. */
    public List<JobView> views() {
        List<JobView> out = new ArrayList<>();
        for (EnrichmentConfig j : jobs) {
            List<Map<String, String>> runs = EnrichmentAuditReader.forConfig(j).runs();
            String lastStatus = "", lastTime = "";
            if (!runs.isEmpty()) {
                Map<String, String> last = runs.get(runs.size() - 1);   // newest run last
                lastStatus = last.getOrDefault("status", "");
                lastTime   = last.getOrDefault("end_time", last.getOrDefault("start_time", ""));
            }
            EnrichmentConfig.Triggers t = j.triggers();
            out.add(new JobView(j.name(), t.onPipeline(), t.scheduleSeconds(),
                    t.hasEvent(), t.hasSchedule(), runs.size(), lastStatus, lastTime));
        }
        return out;
    }

    /** Raw run-audit rows for a job, oldest first. Throws if the job is not hosted here. */
    public List<Map<String, String>> runs(String name) {
        return EnrichmentAuditReader.forConfig(require(name)).runs();
    }

    /** Lineage rows for a job (optionally a single {@code runId}). Throws if not hosted. */
    public List<Map<String, String>> lineage(String name, String runId) {
        return EnrichmentAuditReader.forConfig(require(name)).lineage(runId);
    }

    private EnrichmentConfig require(String name) {
        return config(name).orElseThrow(
                () -> new IllegalArgumentException("no enrichment job named '" + name + "'"));
    }

    /** Lazily create (and cache) the audit ledger writer for a job. */
    private EnrichmentAuditWriter auditFor(EnrichmentConfig job) {
        return auditWriters.computeIfAbsent(job.name(),
                k -> new EnrichmentAuditWriter(EnrichmentAuditWriter.auditDir(job), job.name()));
    }

    /**
     * Translate committed partition paths ({@code col=val/col=val/...}) into the engine's
     * filter form, keeping only columns this job partitions its input by. If a path shares
     * no partition column with the job it is dropped; an empty result means "recompute
     * fully" — the safe fallback when the event can't be scoped.
     */
    private static List<Map<String, String>> toFilter(EnrichmentConfig job, List<String> partitionPaths) {
        Set<String> cols = new HashSet<>(job.input().partitions());
        List<Map<String, String>> out = new ArrayList<>();
        for (String path : partitionPaths) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String kv : path.split("/")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String k = kv.substring(0, eq).trim();
                    if (cols.contains(k)) m.put(k, kv.substring(eq + 1).trim());
                }
            }
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }

    @Override
    public void close() {
        workers.close();   // virtual-thread executor: awaits in-flight recomputes
        log.info("EnrichmentService stopped");
    }
}
