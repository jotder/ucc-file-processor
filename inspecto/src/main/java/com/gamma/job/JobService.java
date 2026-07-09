package com.gamma.job;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
import com.gamma.pipeline.DeletionFence;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.exec.PipelineJobRunner;
import com.gamma.pipeline.exec.TriggerCoalescer;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.metrics.MetricRegistry;
import com.gamma.report.ReportService;
import com.gamma.service.BatchEventBus;
import com.gamma.service.CronExpression;
import com.gamma.service.Scheduler;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import com.gamma.util.LockingRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
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
    /** Optional DuckDB data-plane provenance store for PIPELINE jobs (T21); {@code null} when no backend is configured. */
    private final com.gamma.pipeline.exec.DbProvenanceStore provenanceStore;
    /** Authored-flow store for {@link JobType#PIPELINE} jobs (T32); {@code null} when no write root is configured. */
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
    /** Open Job Type registry (job-framework P0) — replaced the compiled-in {@link JobType} switch; the four
     *  built-ins register here in {@link #registerBuiltins()} and {@link #build} delegates to it. */
    private final JobTypeRegistry registry = new JobTypeRegistry();

    /** Hot-deployable Job Packs (P2c, §12) — off unless {@code -Djobs.packs.dir} is set. */
    private final JobPackManager packs;
    /** Per-run structured Run Log persistence (R5): JSONL under {@code <auditDir>/runlog/}. */
    private final RunLogStore runLogStore;
    /** Per-run Run Artifact persistence (R7, §10): JSONL under {@code <auditDir>/artifacts/}. */
    private final RunArtifactStore runArtifactStore;
    /** Cap on Run Log entries per run (overflow summarized) — {@code -Djobs.runlog.maxEntries}, default 10 000. */
    private final int runLogMax = Integer.getInteger("jobs.runlog.maxEntries", 10_000);
    /** This space's event ledger — the on-signal Trigger source (P1c). Set by the host ({@code SourceService});
     *  {@code null} (e.g. the bare-{@code JobService} test constructors) disables on-signal dispatch. */
    private volatile EventLog eventLog;
    /** One coalescer per on-signal Job, so a burst of matching signals folds into one follow-up Run (§8.4). */
    private final Map<String, TriggerCoalescer> signalCoalescers = new ConcurrentHashMap<>();
    /** Loop cut: a signal-triggered Run beyond this chain depth does not fire (§8.4) — {@code -Djobs.signal.maxChainDepth}. */
    private final int maxChainDepth = Integer.getInteger("jobs.signal.maxChainDepth", 8);
    /** The ledger subscriber this service installed, kept so {@link #close()} can remove it — the default
     *  space's {@link EventLog#global()} is process-wide, so an un-removed subscriber would leak and misfire. */
    private volatile java.util.function.Consumer<Event> signalSubscriber;
    /** Flow ids (authored-flow graph names) of {@link JobType#PIPELINE} jobs currently in flight — fed to the
     *  deletion fence (T32) so a delete that races an active flow-job reader/writer surfaces a conflict. */
    private final Set<String> runningFlows = ConcurrentHashMap.newKeySet();

    /** Live + recently-finished runs by {@code runId}, so {@code GET /jobs/runs/{runId}} can poll a manual
     *  fire (W5). A run is registered {@code RUNNING} at submit and replaced with its terminal
     *  {@link JobRun} on completion. Bounded LRU (oldest evicted) — the durable history stays in the ledger. */
    private static final int LIVE_RUN_CAP = 1000;
    private final Map<String, JobRun> liveRuns = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, JobRun>(64, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JobRun> eldest) {
                    return size() > LIVE_RUN_CAP;
                }
            });

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
     * Full constructor. Adds the authored-flow store and data root that {@link JobType#PIPELINE} jobs need (T32):
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
        this.runLogStore = new RunLogStore(auditDir);
        this.runArtifactStore = new RunArtifactStore(auditDir);
        registerBuiltins();
        // Job Packs (P2c): load hot-deployable types BEFORE building Jobs, so a Job authored against a
        // pack type resolves at construction. Startup-scan signals no-op until the event log is wired.
        this.packs = new JobPackManager(System.getProperty("jobs.packs.dir"), registry,
                (type, sev, payload) -> emitSignal(type, sev, null, "job.packs", payload));
        this.packs.scanAtStartup();
        for (JobConfig c : this.configs) {
            if (c.enabled()) jobs.put(c.name(), build(c));
        }
    }

    /** Register the four built-in Job Types as providers with catalog descriptors (job-framework P0/P2a).
     *  Ids are the lowercased {@link JobType} names, so existing {@code *_job.toon} {@code type:} strings
     *  resolve unchanged. Declared parameters are the real config keys each built-in reads — surfaced by
     *  {@code GET /jobs/types/{id}} (R3). */
    private void registerBuiltins() {
        registry.register(JobTypeProvider.of(new JobTypeDescriptor("enrich", "Enrichment",
                "Runs a Stage-2 enrichment once (full recompute) and publishes a chain commit.",
                List.of(ParameterDecl.required("config", ParamType.STRING, "Path to the enrichment .toon")),
                List.of("pipeline.commit"), List.of()),
                c -> new EnrichJob(c, bus)));
        registry.register(JobTypeProvider.of(new JobTypeDescriptor("report", "Report",
                "Computes a report (status / batch / dataset export) and optionally delivers it.",
                List.of(ParameterDecl.optional("scope", ParamType.STRING, "status", "status | batch | dataset"),
                        ParameterDecl.optional("out_dir", ParamType.STRING, null, "Delivery directory (enables artifact + REPORT_READY)"),
                        ParameterDecl.optional("format", ParamType.STRING, null, "json | csv"),
                        ParameterDecl.optional("dataset", ParamType.DATASET_REF, null, "Dataset id (scope=dataset)")),
                List.of(), List.of(ArtifactDecl.report("report"))),
                c -> new ReportJob(c, reports, dataDir)));
        registry.register(JobTypeProvider.of(new JobTypeDescriptor("maintenance", "Maintenance",
                "Built-in housekeeping task (cleanup / ledger_prune / db_maintenance / compact / materialize).",
                List.of(ParameterDecl.optional("task", ParamType.STRING, "cleanup", "Which maintenance task"),
                        ParameterDecl.optional("dir", ParamType.STRING, null, "Target directory (cleanup / compact)"),
                        ParameterDecl.optional("retention_days", ParamType.INTEGER, "7", "Age threshold in days"),
                        ParameterDecl.optional("store", ParamType.STRING, null, "Store(s) a delete task targets (fenced)")),
                List.of(), List.of()),
                c -> new MaintenanceJob(c, dataDir)));
        registry.register(JobTypeProvider.of(new JobTypeDescriptor("pipeline", "Pipeline",
                "Runs an authored Pipeline over data at rest; emits a commit downstream jobs can chain on.",
                List.of(ParameterDecl.required("flow", ParamType.STRING, "Authored Pipeline id to run"),
                        ParameterDecl.optional("incremental_column", ParamType.STRING, null, "Watermark column for incremental runs")),
                List.of("pipeline.commit"), List.of()),
                this::buildFlowJob));
        // Classpath providers (optional Maven modules — the "classpath way", §12.4). ServiceLoader finds
        // none in the base build; a provider whose id collides with a built-in (registered first) is
        // rejected, fail-closed. Hot-deployable Job Packs (isolated classloaders) arrive in P2c.
        for (JobTypeProvider provider : ServiceLoader.load(JobTypeProvider.class)) {
            try {
                registry.register(provider);
            } catch (RuntimeException e) {
                log.warn("job type provider {} rejected: {}", provider.getClass().getName(), e.getMessage());
            }
        }
    }

    /** Wire the event/signal subscribers and arm cron schedules. */
    public void start() {
        bus.subscribe(this::onBatchEvent);
        int cronCount = 0, eventCount = 0, signalCount = 0;
        for (JobConfig c : configs) {
            if (!c.enabled()) continue;
            if (c.hasCron()) {
                CronExpression expr = c.cronExpression();
                crons.put(c.name(), expr);
                scheduler.cron("job-" + c.name(), expr, zone, () -> submit(c.name(), "schedule"));
                cronCount++;
            }
            if (c.hasEvent()) eventCount++;
            if (c.hasSignal()) { signalCoalescers.put(c.name(), new TriggerCoalescer()); signalCount++; }
        }
        // On-signal Triggers (P1c): subscribe to THIS space's ledger (per-instance subscriber list ⇒
        // space-correct), and mirror each BatchEvent as a pipeline.commit signal so on_signal:pipeline.commit
        // works. Both are no-ops when no event ledger is wired (bare-JobService test constructors).
        if (eventLog != null) {
            signalSubscriber = this::onSignalEvent;
            eventLog.addSubscriber(signalSubscriber);
            bus.subscribe(this::mirrorPipelineCommit);   // bus is per-space, discarded with this service
        }
        packs.startWatching();   // P2c: react to jars dropped/removed at runtime (settle-delayed rescan)
        log.info("JobService started: {} job(s) — {} cron-scheduled, {} event-triggered, {} signal-triggered",
                jobs.size(), cronCount, eventCount, signalCount);
        catchUpMissedFires();
    }

    /** Bind this service to its space's event ledger (the on-signal Trigger source). Call before {@link #start()}. */
    public void eventLog(EventLog eventLog) {
        this.eventLog = eventLog;
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
        return registry.create(c.type(), c);   // registry keys are lowercased ids; create() folds case
    }

    /** A {@link JobType#PIPELINE} job (T32) — requires an authored-flow store (set {@code -Dassist.write.root}). */
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

    /**
     * On-signal dispatch (P1c, §8.2): a Signal landed on this space's ledger — fire every enabled Job whose
     * {@code on_signal:} matches its dotted type (exact or {@code prefix.*}), that isn't the emitter (self-loop
     * guard), and whose {@code when:} guard passes. Firings fold through a per-Job {@link TriggerCoalescer} so a
     * storm collapses into one follow-up Run; the Run inherits the signal's correlation id and chain depth + 1,
     * and a chain deeper than {@link #maxChainDepth} is cut instead of fired (§8.4).
     */
    private void onSignalEvent(Event e) {
        if (!EventType.SIGNAL.equals(e.type())) return;
        Signal sig = Signal.fromEvent(e);
        String emitter = emittingJob(sig.source());
        int newDepth = intOf(sig.payload().get("chainDepth")) + 1;
        for (JobConfig c : configs) {
            if (!c.enabled() || !c.hasSignal()) continue;
            if (!Signals.matchesType(sig.type(), c.onSignal())) continue;
            if (c.name().equals(emitter)) continue;                       // self-loop guard
            if (c.hasWhen() && !WhenGuard.eval(c.when(), sig.payload())) {
                recordSkipped(c.name(), "signal:" + sig.type(), "when guard false");
                continue;
            }
            if (newDepth > maxChainDepth) { cutChain(c.name(), sig, newDepth); continue; }
            String cid = sig.correlationId();
            Firing firing = new Firing(Map.of(), sig.payload());   // §7.2 layer 2: bind: resolves $signal.<field>
            signalCoalescers.computeIfAbsent(c.name(), k -> new TriggerCoalescer())
                    .signal(() -> submitRun(newRunId(c.name()), c.name(), "signal:" + sig.type(), cid, newDepth, firing));
        }
    }

    /** Mirror each committed BatchEvent as a {@code pipeline.commit} signal (§8.3) so a Job may
     *  {@code on_signal: pipeline.commit}. The existing {@code on_pipeline} path (via {@link #onBatchEvent})
     *  is unchanged — the two coexist (no double-fire; different config keys). */
    private void mirrorPipelineCommit(BatchEvent be) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pipeline", be.pipeline());
        payload.put("batchId", be.batchId());
        payload.put("status", be.status());
        payload.put("rows", be.outputRows());
        payload.put("ms", be.durationMs());
        payload.put("parts", be.partitions());
        emitSignal("pipeline.commit", "SUCCESS".equals(be.status()) ? Severity.INFO : Severity.WARNING,
                be.batchId(), "pipeline:" + be.pipeline(), payload);
    }

    /** Emit a framework signal outside a Run (mirror / chain-cut) directly to this space's ledger. */
    private void emitSignal(String type, Severity sev, String correlationId, String source, Map<String, Object> payload) {
        EventLog el = eventLog;
        if (el == null) return;
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.putIfAbsent("chainDepth", 0);
        el.emit(new Signal(UUID.randomUUID().toString(), type, Instant.now(), source, correlationId, sev, p).toEvent());
    }

    /** A→B→A loop protection: the chain is too deep — don't fire; emit a {@code job.chain.cut} WARNING (§8.4). */
    private void cutChain(String name, Signal sig, int depth) {
        log.warn("[JOB] signal chain cut at depth {} (max {}) — not firing '{}' on '{}'",
                depth, maxChainDepth, name, sig.type());
        emitSignal("job.chain.cut", Severity.WARNING, sig.correlationId(), "job:" + name,
                Map.of("job", name, "signalType", sig.type(), "chainDepth", depth, "maxChainDepth", maxChainDepth));
    }

    private void recordSkipped(String name, String trigger, String message) {
        Job job = jobs.get(name);
        if (job == null) return;
        String now = LocalDateTime.now().format(TS);
        record(new JobRun(newRunId(name), name, job.type(), trigger, now, now, "SKIPPED", 0L, message));
    }

    /** The Job name from a signal source of the form {@code job:<name>/run:<id>}, else {@code null}. */
    private static String emittingJob(String source) {
        if (source == null || !source.startsWith("job:")) return null;
        int slash = source.indexOf('/', 4);
        return slash < 0 ? source.substring(4) : source.substring(4, slash);
    }

    private static int intOf(Object o) {
        try { return o == null ? 0 : (int) Double.parseDouble(String.valueOf(o)); }
        catch (RuntimeException e) { return 0; }
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
        return triggerRun(name, actor).isPresent();
    }

    /**
     * Run a job once by name and return its {@code runId} (W5) so an async HTTP caller can poll
     * {@link #runById}. Empty if no such (enabled) job. Attribution matches {@link #trigger(String, String)}.
     */
    public Optional<String> triggerRun(String name, String actor) {
        return triggerRun(name, actor, Map.of());
    }

    /**
     * As {@link #triggerRun(String, String)} but carrying explicit trigger {@code args} — the manual
     * {@code POST /jobs/{name}/trigger} body's {@code params:} (§7.2 layer 1). These override any static
     * config {@code args:}/{@code params:} and the deduced context for this fire.
     */
    public Optional<String> triggerRun(String name, String actor, Map<String, String> args) {
        if (!jobs.containsKey(name)) return Optional.empty();
        String runId = newRunId(name);
        String trigger = actor == null || actor.isBlank() ? "manual" : "manual:" + actor.trim();
        submitRun(runId, name, trigger, runId, 0, new Firing(args == null ? Map.of() : args, Map.of()));
        return Optional.of(runId);
    }

    /** A live or recently-finished run by its id, for polling; empty once evicted past the LRU cap. */
    public Optional<JobRun> runById(String runId) {
        return runId == null ? Optional.empty() : Optional.ofNullable(liveRuns.get(runId));
    }

    private String newRunId(String name) {
        return name.toLowerCase().replace(' ', '_') + "-"
                + LocalDateTime.now().format(RUN_TS) + "-" + seq.incrementAndGet();
    }

    private void submit(String name, String trigger) {
        if (jobs.containsKey(name)) submitRun(newRunId(name), name, trigger);
    }

    /**
     * Per-firing dynamic parameter inputs (P3a-2, §7.2): {@code args} are explicit trigger args (a manual
     * {@code POST} body's {@code params:}); {@code signalPayload} is the firing Signal's payload, against
     * which a Job's {@code bind:} map resolves {@code $signal.<field>}. Both empty for cron/event/catch-up.
     */
    private record Firing(Map<String, String> args, Map<String, Object> signalPayload) {
        static final Firing NONE = new Firing(Map.of(), Map.of());
    }

    /** Register the run as {@code RUNNING} and execute it off the caller's thread; a fresh correlation chain. */
    private void submitRun(String runId, String name, String trigger) {
        submitRun(runId, name, trigger, runId, 0, Firing.NONE);
    }

    /**
     * As above, carrying the correlation context: {@code correlationId} ties a Run to the chain that started it
     * (a fresh Run uses its own runId), {@code chainDepth} is its position in a signal chain (0 for cron/manual/
     * event/catch-up; the firing signal's depth + 1 for on-signal Runs) — stamped onto every signal the Run emits.
     * {@code firing} carries this fire's dynamic parameter inputs (§7.2 layers 1–2).
     */
    private void submitRun(String runId, String name, String trigger, String correlationId, int chainDepth, Firing firing) {
        Job job = jobs.get(name);
        if (job == null) return;
        String start = LocalDateTime.now().format(TS);
        liveRuns.put(runId, new JobRun(runId, name, job.type(), trigger, start, null, "RUNNING", 0L, null));
        // The default space runs with no MDC (it is the fallback namespace everywhere); a named space sets it so
        // this job run's events/metrics/acquisition route to that space. Fresh per-task virtual thread → clear after.
        boolean scoped = !EventLog.DEFAULT_SPACE_ID.equals(spaceId);
        workers.submit(() -> {
            if (scoped) MDC.put(EventLog.SPACE_MDC_KEY, spaceId);
            try {
                runJob(runId, name, trigger, start, correlationId, chainDepth, firing);
            } finally {
                if (scoped) MDC.remove(EventLog.SPACE_MDC_KEY);
            }
        });
    }

    private void runJob(String runId, String name, String trigger, String start, String correlationId, int chainDepth, Firing firing) {
        Job job = jobs.get(name);
        if (job == null) return;
        runner.runExclusiveOrSkip(name, () -> {
            fenceDelete(name);   // T25: surface a conflict if a declared delete races an active reader/writer
            String flowId = trackFlowStart(job, name);   // T32: mark a flow job's stores active for the fence
            JobConfig cfg = configFor(name).orElse(null);
            Map<String, String> params = cfg != null ? cfg.params() : Map.of();
            RunContext ctx = new RunContext(runId, spaceId, name, trigger, correlationId, chainDepth,
                    params, runLogStore, runLogMax, runArtifactStore);
            // P3a/P3a-2: resolve the Job Type's declared parameters across the §7.2 ladder — trigger args
            // (this fire's explicit args over any static config args:) → signal bind: → config params: →
            // deduce → default. A missing required parameter fails the Run REJECTED before any user code.
            List<ParameterDecl> decls = registry.descriptor(job.type())
                    .map(JobTypeDescriptor::parameters).orElse(List.of());
            Map<String, String> args = new LinkedHashMap<>(cfg != null ? cfg.args() : Map.of());
            args.putAll(firing.args());                         // explicit manual args win over static config args:
            Map<String, String> bind = cfg != null ? cfg.bind() : Map.of();
            ParameterResolver.Resolution pr = ParameterResolver.resolve(decls, args, bind, params,
                    new ParameterResolver.Context(runId, Instant.now(), trigger, zone,
                            () -> ledger.lastSuccessEnd(name), this::upstreamArtifact, firing.signalPayload()));
            if (!pr.missingRequired().isEmpty()) {
                String miss = String.join(", ", pr.missingRequired());
                ctx.log().error("run rejected: missing required parameter(s): " + miss, null);
                ctx.signals().emit("job.run.rejected", Severity.WARNING,
                        Map.of("job", name, "run", runId, "missing", pr.missingRequired()));
                if (flowId != null) runningFlows.remove(flowId);
                record(new JobRun(runId, name, job.type(), trigger, start,
                        LocalDateTime.now().format(TS), "REJECTED", 0L,
                        "missing required parameter(s): " + miss));
                return;
            }
            ctx.params(pr.resolved());
            ctx.log().info("run started", "trigger", trigger, "params", pr.resolved());   // resolved Parameter Context (R2/R5)
            ctx.signals().emit("job.run.started", Severity.INFO,
                    Map.of("job", name, "run", runId, "trigger", trigger));
            JobResult res;
            boolean threw = false;
            try {
                res = job.run(ctx);
            } catch (Exception e) {
                threw = true;
                log.error("Job '{}' ({}) failed", name, trigger, e);
                ctx.log().error("run failed", e);
                res = JobResult.failed(String.valueOf(e.getMessage()),
                        0L);
            } finally {
                if (flowId != null) runningFlows.remove(flowId);
            }
            ctx.log().info("run completed", "status", res.status(), "durationMs", res.durationMs());
            // One terminal lifecycle signal: job.run.failed on a thrown exception, else job.run.completed.
            if (threw)
                ctx.signals().emit("job.run.failed", Severity.CRITICAL,
                        Map.of("job", name, "run", runId, "outcome", res.status(), "message", String.valueOf(res.message())));
            else
                ctx.signals().emit("job.run.completed", res.success() ? Severity.INFO : Severity.WARNING,
                        Map.of("job", name, "run", runId, "outcome", res.status(), "durationMs", res.durationMs()));
            JobRun run = new JobRun(runId, name, job.type(), trigger, start,
                    LocalDateTime.now().format(TS), res.status(), res.durationMs(), res.message());
            record(run);
            MetricRegistry.global().inc("inspecto_jobs_total", "Config-driven job executions",
                    Map.of("job", name, "type", job.type(), "status", res.status()));
            MetricRegistry.global().observe("inspecto_job_duration_seconds", "Job wall time",
                    Map.of("job", name), res.durationMs() / 1000.0);
            log.info("[JOB] {} ({}) {} in {}ms — {}",
                    name, trigger, res.status(), res.durationMs(), res.message());
        }, () ->   // a previous run is still in flight — don't overlap
            record(new JobRun(runId, name, job.type(), trigger, start,
                    LocalDateTime.now().format(TS), "SKIPPED", 0L, "previous run still in flight")));
    }

    /** Record a terminal run to both the durable ledger and the live-run registry (so a poll sees the result). */
    private void record(JobRun run) {
        ledger.record(run);
        liveRuns.put(run.runId(), run);
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
        if (jc == null || !"maintenance".equals(jc.type())) return;
        String storeCsv = jc.opt("store", "").trim();
        if (storeCsv.isBlank()) return;
        List<String> stores = java.util.Arrays.stream(storeCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (!stores.isEmpty()) guard.check(stores);   // the guard logs the warning + emits the conflict event
    }

    /**
     * Mark a {@link JobType#PIPELINE} job's flow as running for the deletion fence (T32) and return its flow id;
     * {@code null} for a non-flow job. The id is the authored-flow graph name (the job's {@code flow} param),
     * so it matches the flow names {@link DeletionFence#check} derives from the authored flows the live
     * {@code SourceService} feeds it — a delete racing this flow's store then surfaces as a conflict.
     */
    private String trackFlowStart(Job job, String name) {
        if (!"pipeline".equals(job.type())) return null;
        String flowId = configFor(name).map(c -> c.opt("flow", name)).orElse(name);
        runningFlows.add(flowId);
        return flowId;
    }

    /** The configured job by name (the run path keys by name; configs is the source of truth for params). */
    private Optional<JobConfig> configFor(String name) {
        return configs.stream().filter(c -> c.name().equals(name)).findFirst();
    }

    /**
     * Flow ids of {@link JobType#PIPELINE} jobs currently in flight. The live {@code SourceService} unions this
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
            out.add(new JobView(c.name(), c.type(), c.cron(), c.onPipeline(), c.enabled(),
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

    /** The structured Run Log entries for one run (R5), in write order; empty if unknown or never logged. */
    public List<RunLogEntry> runLog(String runId) {
        return runLogStore.read(runId);
    }

    /** Run Artifacts recorded by one run (R7, §10), in write order — {@code GET /jobs/{name}/runs/{runId}/artifacts}. */
    public List<RunArtifact> runArtifacts(String runId) {
        return runArtifactStore.read(runId);
    }

    /** Artifacts of a job's most recent successful run (R7) — {@code GET /jobs/{name}/artifacts/latest}; empty if none. */
    public List<RunArtifact> latestArtifacts(String name) {
        return ledger.lastSuccessRunId(name).map(runArtifactStore::read).orElse(List.of());
    }

    /** One named artifact from a job's latest successful run (highest seq wins) — the {@code $upstream(...)} lookup (§7.3). */
    private Optional<RunArtifact> upstreamArtifact(String job, String artifact) {
        RunArtifact hit = null;
        for (RunArtifact a : latestArtifacts(job)) if (artifact.equals(a.name())) hit = a;
        return Optional.ofNullable(hit);
    }

    /** Every registered Job Type's descriptor (R3, {@code GET /jobs/types}) — built-ins now, modules/packs later. */
    public List<JobTypeDescriptor> jobTypes() {
        return registry.descriptors();
    }

    /** One Job Type's descriptor by id (R3, {@code GET /jobs/types/{id}}), if registered. */
    public Optional<JobTypeDescriptor> jobType(String id) {
        return registry.descriptor(id);
    }

    /** Loaded Job Pack inventory (id, version, hash, types, state) — {@code GET /jobs/packs} (§12, R8). */
    public List<Map<String, Object>> jobPacks() {
        return packs.inventory();
    }

    /** Force a Job Pack dir reconcile (load/reload/unload); returns the transition summary — {@code POST /jobs/packs/rescan}. */
    public Map<String, Object> rescanPacks() {
        return packs.rescan();
    }

    /** Whether any job by this name is registered and enabled. */
    public boolean has(String name) { return jobs.containsKey(name); }

    @Override
    public void close() {
        if (eventLog != null && signalSubscriber != null) eventLog.removeSubscriber(signalSubscriber);
        packs.close();     // P2c: stop the watch thread, close pack classloaders
        workers.close();   // virtual-thread executor: awaits in-flight job runs
        ledger.close();
        if (provenanceStore != null) provenanceStore.close();
        log.info("JobService stopped");
    }
}
