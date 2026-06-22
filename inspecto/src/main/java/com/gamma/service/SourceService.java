package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.catalog.CatalogOverlay;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.IngestProgress;
import com.gamma.etl.PipelineConfig;
import com.gamma.flow.DeletionFence;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowStore;
import com.gamma.flow.FlowTrigger;
import com.gamma.flow.PipelineLift;
import com.gamma.flow.exec.TriggerCoalescer;
import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventStore;
import com.gamma.event.EventType;
import com.gamma.event.SavedViewStore;
import com.gamma.inspector.MultiSourceProcessor;
import com.gamma.inspector.SourceProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Long-running service that hosts the ETL: it loads a registry of pipeline configs,
 * polls them on a schedule, runs them concurrently under a global budget, and emits
 * batch-commit events on a {@link BatchEventBus} that downstream stages (Stage-2
 * enrichment, M2) subscribe to.
 *
 * <h3>Model</h3>
 * <ul>
 *   <li><b>Registry</b> — config <em>paths</em> (files / dirs of {@code *_pipeline.toon}).
 *       Each poll cycle reloads them, so every cycle is a fresh run (new run timestamp)
 *       and config edits are picked up without a restart.</li>
 *   <li><b>Scheduler</b> — one interval-driven cycle that runs the whole registry via
 *       {@link MultiSourceProcessor#runAll(List, int, java.util.function.Consumer)},
 *       which bounds concurrent sources to the global run budget.</li>
 *   <li><b>Recovery</b> — on startup, report each pipeline's previously committed
 *       batches from the commit log via {@link StatusStore}. Batch atomicity (commit
 *       ordering: markers last) + marker-based dedup already make an interrupted batch
 *       safe to reprocess next cycle; the commit log provides the visibility.</li>
 *   <li><b>Event bus</b> — {@link #eventBus()} is handed to the runners as the commit
 *       sink; subscribe to it to react to committed batches.</li>
 *   <li><b>Enrichment</b> — an optional registry of {@code *_enrich.toon} jobs is hosted
 *       by an {@link EnrichmentService} sharing this service's bus and scheduler, so
 *       Stage-2 reports recompute incrementally on batch-commit events and fully on a
 *       schedule. See {@link EnrichmentService}.</li>
 * </ul>
 *
 * <p>CLI: {@code java -cp file-processor.jar com.gamma.service.SourceService
 * [-Dservice.poll.seconds=N] [-Dservice.max.runs=M] <config.toon | dir> [more ...]}.
 * Paths are scanned for {@code *_pipeline.toon} (Stage-1 sources) and
 * {@code *_enrich.toon} (Stage-2 enrichment jobs).
 */
@PublicApi(since = "2.2.0")
public final class SourceService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SourceService.class);

    /**
     * The active set of pipeline config paths. A {@link CopyOnWriteArrayList} (not an immutable copy)
     * so new pipelines can be added at runtime via {@link #registerPipeline(Path)} — the poll cycle
     * re-indexes from this list each tick, so an addition is picked up on the next cycle without a
     * restart. Mutated only under {@link #ingestLock}; iteration is snapshot-safe.
     */
    private final List<Path> registry;
    private final long pollSeconds;
    private final int  maxConcurrentRuns;
    private final BatchEventBus bus = new BatchEventBus();
    /** Append-only operational event store (Phase 1, v4.2.0) — the durable record of "what happened",
     *  backing the {@code /events*} API. Built from {@code -Devents.backend} (memory|parquet) and
     *  installed into the process-wide {@link EventLog} so the SLF4J capture appender and the domain
     *  emitters share one sink. Assigned in the full constructor; closed in {@link #close()}. */
    private final EventStore events;
    /** Operator-saved event views (Phase 1, v4.2.0) — backs {@code /events/views}. File-backed when
     *  {@code -Devents.views.file} is set, otherwise in-memory only. */
    private final SavedViewStore savedViews;
    /** Mutable operational-object store (Phase 2, v4.3.0) — the Layer-2 Alert Center backing the
     *  {@code /objects} API. Built from {@code -Dobjects.backend} (memory|db); closed in {@link #close()}. */
    private final com.gamma.ops.ObjectStore objectStore;
    /** Append-only correlation-link store (Phase 4, v4.5.0) — the OBJECT_LINK graph behind the
     *  {@code /objects/{id}/links} + {@code /graph} API. Same {@code -Dobjects.backend} toggle; closed in {@link #close()}. */
    private final com.gamma.ops.link.LinkStore linkStore;
    /** Append-only evidence/notes store (Phase 4 follow-up, v4.6.0) — comments + attachment refs behind
     *  {@code /objects/{id}/comments|attachments}. Same {@code -Dobjects.backend} toggle; closed in {@link #close()}. */
    private final com.gamma.ops.note.NoteStore noteStore;
    /** Object Engine + Workflow Engine over {@link #objectStore} + {@link #linkStore} + {@link #noteStore}. */
    private final com.gamma.ops.ObjectService objects;
    /** Loaded {@code *_rca.toon} templates by name (Phase 4) — backs {@code GET /rca/templates} and
     *  {@code POST /objects/{id}/rca {template:<name>}}. Populated by {@link #fromArgs} or
     *  {@link #registerRcaTemplate}; empty otherwise. */
    private final Map<String, com.gamma.ops.rca.RcaTemplate> rcaTemplates = new ConcurrentHashMap<>();
    /** Reusable connection profiles by id (Data Acquisition) — loaded from {@code *_connection.toon}; backs
     *  {@code GET /connections} + {@code POST /connections/{id}/test} and the {@code source.connection} binding. */
    private final Map<String, com.gamma.acquire.ConnectionProfile> connections = new ConcurrentHashMap<>();
    /** Authoritative on-disk audit reader; also the sync source when a DB backend is used. */
    private final FileStatusStore fileStatus = new FileStatusStore();
    /** The read surface the Control API + observability query — file- or DB-backed (M5). */
    private final StatusStore status;
    private final Scheduler scheduler = new Scheduler();
    private final EnrichmentService enrichment;
    /** Aggregates audit into status / batch-audit reports for the Control API (v2.8.0). */
    private final ReportService reports = new ReportService(this);
    /** Config-driven cron/event jobs (v2.8.0); null when none are registered. */
    private final JobService jobs;
    private final MetricsService metrics =
            new MetricsService(this, com.gamma.metrics.MetricRegistry.global());
    /** Pipeline names an operator has paused; the poll cycle skips them (Control API, M3). */
    private final Set<String> paused = ConcurrentHashMap.newKeySet();
    /** Pipeline names currently mid-ingest. Marked around each run cycle / operator trigger; backs the
     *  "under processing" signal of {@link #inboxStatus(String)}. Ingest is synchronous within a cycle,
     *  so a pipeline is "running" only while its batches are actively being processed. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /** The filesystem locations this service's stores read/write — {@link SpaceRoot#legacy()} for the
     *  single-tenant constructors, or a per-space directory when hosted by a {@code SpaceManager}. */
    private final SpaceRoot root;
    /** This service's space id ({@code "default"} for the legacy single-tenant layout). */
    private final String spaceId;
    /** This space's event log — {@link EventLog#global()} for the {@code default} space, a fresh instance
     *  otherwise; {@linkplain EventLog#register registered} under {@link #spaceId} so MDC-routed emitters
     *  (the capture appender, the poll-path telemetry) reach it. */
    private final EventLog eventLog;
    /** Authored-flow store ({@link SpaceRoot#flowsDir()}); lets the deletion fence (T32) see flow jobs as
     *  store producers/consumers. {@code null} when no write root is configured. */
    private final FlowStore flowStore;
    /** Serializes ingest cycles so an operator-triggered run (Control API {@code /trigger},
     *  {@code /pipelines/{name}/trigger}) can never overlap the scheduled poll cycle or another
     *  trigger. The scheduler is already non-overlapping (fixed-delay); this guards the
     *  cross-entrypoint case. A waiting caller re-evaluates the inbox after acquiring the lock,
     *  by which time the prior cycle has written its {@code .processed} markers — so
     *  already-ingested files are skipped rather than double-processed. */
    private final ReentrantLock ingestLock = new ReentrantLock();
    /** T13 / §3.8 — entry-node trigger driving. Per-pipeline last-run epoch (ms), so the loop scheduler
     *  gates a {@code schedule:{every}}/{@code cron} pipeline by its own cadence instead of running every
     *  active flow each tick. A pipeline with no {@code trigger:} (every legacy/lifted config) is
     *  {@code DEFAULT_POLL} and still runs every cycle, so behaviour is unchanged. */
    private final Map<String, Long> lastRunAtMs = new ConcurrentHashMap<>();
    /** Per-pipeline coalescer for {@code event}-triggered flows: an upstream-commit storm collapses to one
     *  non-overlapping run (the in-process {@code ingestLock} debounce, lifted to the flow grain). */
    private final Map<String, TriggerCoalescer> eventCoalescers = new ConcurrentHashMap<>();
    /** Off-bus worker for event-triggered runs. The bus delivers synchronously on the publishing (runner)
     *  thread while {@link #runAllOnce} holds {@link #ingestLock}; running a triggered pipeline inline would
     *  deadlock, so — like {@link JobService} — the handler hands off here. */
    private final ExecutorService triggerWorkers = Executors.newVirtualThreadPerTaskExecutor();
    /** Zone for evaluating {@code cron} triggers (mirrors {@link JobService}). */
    private final ZoneId triggerZone = ZoneId.systemDefault();
    /** Epoch the service started — the cron "last fire" baseline before a pipeline has ever run. */
    private final long serviceStartMs = System.currentTimeMillis();
    /** Optional embedded assist agent (v3.0, M0): discovered via {@link ServiceLoader} at
     *  {@link #start()} or registered explicitly with {@link #registerAgent(AssistAgent)};
     *  {@code null} when the {@code file-processor-agent} module is absent. */
    private volatile AssistAgent agent;
    /** Loaded {@code *_meta.toon} semantic models (KPI catalog + domain notes) feeding the catalog. */
    private final List<SemanticModel> semanticModels;
    /** The metadata graph / data catalog (M2): config-derived structure + lazy operational overlay. */
    private final MetadataGraphService catalog;
    /** The read-only config seam (pipelines + enrichments + semantics) the catalog assembles from;
     *  also exposed to the assist agent (M8) so {@code report-sql} can resolve a pipeline/job name to
     *  its config without a write-bearing handle. */
    private final ConfigSource configSource;
    /** O(1) index of loaded pipeline configs keyed by in-file identity (M2 config keystone, v3.2.0):
     *  backs pathFor/configFor/activeRegistry/pipelines and the catalog's ConfigSource without the
     *  former per-call O(n) re-parse. Rebuilt at construction and at the top of every poll cycle. */
    private final ConfigRegistry configRegistry;

    /** A pipeline's identity + current state, for the Control API's listing. */
    public record PipelineView(String name, String configPath, boolean paused, int committedBatches) {}

    public SourceService(List<Path> registry, long pollSeconds, int maxConcurrentRuns) {
        this(registry, List.of(), pollSeconds, maxConcurrentRuns);
    }

    public SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                         long pollSeconds, int maxConcurrentRuns) {
        this(registry, enrichJobs, pollSeconds, maxConcurrentRuns, null);
    }

    /**
     * @param statusStore the read surface for the Control API + observability. When this
     *                    is a {@link DbStatusStore}, the service projects the on-disk audit
     *                    into it at startup and after each poll cycle; {@code null} falls
     *                    back to the file-backed store (the on-disk audit read directly).
     */
    public SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                         long pollSeconds, int maxConcurrentRuns, StatusStore statusStore) {
        this(registry, enrichJobs, List.of(), pollSeconds, maxConcurrentRuns, statusStore);
    }

    /**
     * Full constructor (v2.8.0). Adds a registry of config-driven {@link JobConfig}s —
     * cron/event/manual jobs (ingest, enrich, report, maintenance) hosted by a
     * {@link JobService} on this service's bus and scheduler.
     *
     * @param jobConfigs config-driven jobs; empty disables the job layer
     */
    public SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                         List<JobConfig> jobConfigs, long pollSeconds, int maxConcurrentRuns,
                         StatusStore statusStore) {
        this(registry, enrichJobs, jobConfigs, List.of(), pollSeconds, maxConcurrentRuns, statusStore);
    }

    /**
     * Full constructor (v3.2.0). Adds the {@code *_meta.toon} semantic models that feed the
     * metadata graph / data catalog ({@link #catalog()}). The catalog projects the configured
     * pipelines, schemas, event tables, Stage-2 transforms, and this semantic layer into a typed,
     * traversable graph, with operational state overlaid lazily from the existing audit reads.
     *
     * @param semanticModels loaded {@code *_meta.toon} models (KPI catalog + domain notes); empty is fine
     */
    public SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                         List<JobConfig> jobConfigs, List<SemanticModel> semanticModels,
                         long pollSeconds, int maxConcurrentRuns, StatusStore statusStore) {
        this(registry, enrichJobs, jobConfigs, semanticModels, List.of(), pollSeconds,
                maxConcurrentRuns, statusStore);
    }

    /**
     * Full constructor (v4.1, B5). Adds operator-saved {@code *_alert.toon} rules, executed by a
     * deterministic {@link com.gamma.alert.AlertService} on this service's bus (the runtime half of
     * the agent's draft-only {@code diagnose-and-alert}); empty disables the alert layer.
     *
     * @param alertRules loaded alert rules; empty is fine
     */
    public SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                         List<JobConfig> jobConfigs, List<SemanticModel> semanticModels,
                         List<com.gamma.alert.AlertRule> alertRules,
                         long pollSeconds, int maxConcurrentRuns, StatusStore statusStore) {
        this(registry, enrichJobs, jobConfigs, semanticModels, alertRules,
                pollSeconds, maxConcurrentRuns, statusStore, SpaceRoot.legacy());
    }

    /**
     * Full constructor (multi-space). Adds the {@link SpaceRoot} that decides where this service's stores
     * read/write — {@link SpaceRoot#legacy()} for the single-tenant entry points above, or a per-space
     * directory when hosted by a {@code SpaceManager}. All earlier constructors delegate here.
     */
    SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                  List<JobConfig> jobConfigs, List<SemanticModel> semanticModels,
                  List<com.gamma.alert.AlertRule> alertRules,
                  long pollSeconds, int maxConcurrentRuns, StatusStore statusStore, SpaceRoot root) {
        this.root              = root;
        this.spaceId           = root.id();
        // The default space reuses the process-wide log (so legacy single-tenant behaviour is unchanged and
        // the capture appender's global fallback still hits it); a hosted space gets its own instance.
        this.eventLog          = "default".equals(spaceId) ? EventLog.global() : EventLog.create();
        EventLog.register(spaceId, eventLog);
        this.flowStore         = ServiceStores.openFlowStore(root);
        this.registry          = new CopyOnWriteArrayList<>(registry);
        this.pollSeconds       = Math.max(1, pollSeconds);
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.status            = statusStore != null ? statusStore : fileStatus;
        this.enrichment        = enrichJobs.isEmpty()
                ? null
                : new EnrichmentService(enrichJobs, bus, scheduler);
        this.jobs              = jobConfigs.isEmpty()
                ? null
                : new JobService(jobConfigs, bus, scheduler, reports,
                        System.getProperty("jobs.audit.dir", root.auditDir()), ServiceStores.openJobRunStore(root),
                        flowStore, System.getProperty("data.dir", root.dataDir()), ServiceStores.openProvenanceStore(root));
        if (this.jobs != null) this.jobs.deletionGuard(this::checkDeletion);   // T25: fence delete jobs
        this.semanticModels    = List.copyOf(semanticModels);
        // Invalidate the catalog whenever configs are (re)indexed — the registry is now the
        // config-change signal the M1 catalog plan anticipated.
        this.configRegistry    = new ConfigRegistry(this::invalidateCatalog);

        // The catalog reads configs through a ConfigSource seam, now backed by the O(1) registry
        // (the seam was designed for exactly this swap — MetadataGraphService is unchanged). It
        // overlays operational state by reusing the existing StatusStore / EnrichmentService reads.
        ConfigSource configSource = new ConfigSource() {
            public List<PipelineConfig> pipelines() { return configRegistry.configs(); }
            public List<EnrichmentConfig> enrichments() {
                return enrichment != null ? enrichment.configs() : List.of();
            }
            public List<SemanticModel> semantics() { return SourceService.this.semanticModels; }
        };
        this.configSource = configSource;
        // Object Engine (Phase 2, v4.3.0): the mutable Layer-2 store for managed objects (alerts now;
        // issues/cases later). Built from -Dobjects.backend (memory|db); always present so /objects
        // works even with no alert rules. Fired alerts are promoted into it by the AlertService below.
        this.objectStore = ServiceStores.openObjectStore(root);
        this.linkStore = ServiceStores.openLinkStore(root);
        this.noteStore = ServiceStores.openNoteStore(root);
        this.objects = new com.gamma.ops.ObjectService(objectStore, java.util.Map.of(), linkStore, noteStore);
        // Phase D2: promote selected domain events to managed objects (SEQUENCE_GAP → ALERT) via an EventLog
        // subscriber. Registered unconditionally (independent of *_alert.toon rules — a gap is not a batch
        // metric) and de-registered in close() so repeated service instances don't accumulate listeners.
        this.eventObjectBridge = new com.gamma.ops.EventObjectBridge(this.objects)::onEvent;
        this.eventLog.addSubscriber(this.eventObjectBridge);
        // Alert engine (v4.1, B5): deterministic, lean-core, event-driven. Subscribed here (before
        // start()) so it sees the first terminal batch; null when no *_alert.toon was loaded. Phase 2:
        // also persists each fired alert as a managed ALERT object via the Object Engine above.
        this.alerting = alertRules.isEmpty() ? null
                : new com.gamma.alert.AlertService(alertRules, configSource, this.status, this.objects);
        if (alerting != null) {
            bus.subscribe(alerting::onEvent);
            log.info("Alert engine armed with {} rule(s)", alertRules.size());
        }
        // Event engine (Phase 1, v4.2.0): the append-only record of "what happened". Built from
        // -Devents.backend (memory|parquet), installed into the process-wide EventLog so the SLF4J
        // capture appender (INFO+) and the domain emitters below share one sink, and subscribed to the
        // bus here (before start()) so the first terminal batch is recorded as a structured event.
        this.events = ServiceStores.openEventStore(root);
        this.eventLog.installStore(events);
        bus.subscribe(this::onBatchEvent);
        String viewsFile = System.getProperty("events.views.file");
        this.savedViews = new SavedViewStore(viewsFile == null ? null : Path.of(viewsFile));
        CatalogOverlay.Stage2Reads stage2 = enrichment == null ? null : new CatalogOverlay.Stage2Reads() {
            public boolean hosts(String job) { return enrichment.config(job).isPresent(); }
            public List<Map<String, String>> runs(String job) { return enrichment.runs(job); }
            public List<Map<String, String>> lineage(String job, String runId) {
                return enrichment.lineage(job, runId);
            }
        };
        this.catalog = new MetadataGraphService(configSource, new CatalogOverlay(this::configFor, status, stage2));

        // Initial population so the read surface (catalog, pathFor, configFor, pipelines) is live
        // before the first poll cycle. Unloadable configs are warned and skipped.
        configRegistry.rebuild(this.registry);
    }

    /** Drop the catalog's cached structural graph; the next access rebuilds it (config-only, cheap). */
    private void invalidateCatalog() {
        if (catalog != null) {
            catalog.invalidate();
        }
    }

    /** The alert execution engine (v4.1, B5), or {@code null} when no {@code *_alert.toon} loaded. */
    private final com.gamma.alert.AlertService alerting;

    /** The EventLog→ObjectService bridge (Phase D2); held so {@link #close()} can de-register it. */
    private final java.util.function.Consumer<com.gamma.event.Event> eventObjectBridge;

    /** The alert engine, when any {@code *_alert.toon} rules are armed — backs {@code /alerts}. */
    public java.util.Optional<com.gamma.alert.AlertService> alertService() {
        return java.util.Optional.ofNullable(alerting);
    }

    /** The bus carrying committed-batch events; subscribe before {@link #start()}. */
    public BatchEventBus eventBus() {
        return bus;
    }

    /** The append-only operational event store backing the {@code /events*} API (Phase 1, v4.2.0). */
    public EventStore events() {
        return events;
    }

    /** Operator-saved event views (Phase 1, v4.2.0) — always present (in-memory when no file set). */
    public SavedViewStore savedViews() {
        return savedViews;
    }

    /** The Object Engine (Phase 2, v4.3.0) — managed operational objects + their workflows; backs the
     *  {@code /objects} API and is where fired alerts are persisted as ALERT objects. */
    public com.gamma.ops.ObjectService objects() {
        return objects;
    }

    /** Register an RCA template (Phase 4), keyed by {@link com.gamma.ops.rca.RcaTemplate#name()}; {@code null} ignored. */
    public void registerRcaTemplate(com.gamma.ops.rca.RcaTemplate template) {
        if (template != null) rcaTemplates.put(template.name(), template);
    }

    /** All registered RCA templates by name (Phase 4) — backs {@code GET /rca/templates}. */
    public Map<String, com.gamma.ops.rca.RcaTemplate> rcaTemplates() {
        return Map.copyOf(rcaTemplates);
    }

    /** A registered RCA template by name, if any (Phase 4). */
    public java.util.Optional<com.gamma.ops.rca.RcaTemplate> rcaTemplate(String name) {
        return java.util.Optional.ofNullable(name == null ? null : rcaTemplates.get(name.trim()));
    }

    /** Register a connection profile (Data Acquisition), keyed by {@link com.gamma.acquire.ConnectionProfile#id};
     *  {@code null} ignored. A later registration with the same id replaces the earlier one. */
    public void registerConnection(com.gamma.acquire.ConnectionProfile profile) {
        if (profile != null) {
            connections.put(profile.id(), profile);
            // Also publish into the process-wide registry so the static poll path (SourceConnectors.forConfig)
            // can resolve a pipeline's source.connection binding to a remote connector (Phase E).
            com.gamma.acquire.ConnectionRegistry.register(profile);
        }
    }

    /** All registered connection profiles by id — backs {@code GET /connections}. */
    public Map<String, com.gamma.acquire.ConnectionProfile> connections() {
        return Map.copyOf(connections);
    }

    /** A registered connection profile by id, if any — backs {@code GET /connections/{id}} + the test action. */
    public java.util.Optional<com.gamma.acquire.ConnectionProfile> connection(String id) {
        return java.util.Optional.ofNullable(id == null ? null : connections.get(id.trim()));
    }

    /**
     * Bus bridge: turn every terminal {@link BatchEvent} into a structured {@link Event}
     * ({@link EventType#BATCH_COMMITTED} on SUCCESS, {@link EventType#BATCH_FAILED} otherwise), keyed
     * by {@code correlationId = batchId} so an investigation can pivot from a batch to everything that
     * happened around it. Subscribed before {@link #start()} so the first commit of a poll cycle is
     * recorded.
     */
    private void onBatchEvent(BatchEvent e) {
        boolean ok = "SUCCESS".equalsIgnoreCase(e.status());
        Event.Builder b = Event.builder(ok ? EventType.BATCH_COMMITTED : EventType.BATCH_FAILED)
                .level(ok ? EventLevel.INFO : EventLevel.ERROR)
                .source(SourceService.class.getName())
                .pipeline(e.pipeline())
                .correlationId(e.batchId())
                .message((ok ? "Batch committed: " : "Batch failed: ") + e.pipeline() + "/" + e.batchId())
                .attr("batchId", e.batchId())
                .attr("outputRows", e.outputRows())
                .attr("durationMs", e.durationMs())
                .attr("rejectedCount", e.rejectedCount())
                .attr("partitions", e.partitions() == null ? 0 : e.partitions().size());
        if (!ok) {
            b.attr("error", e.error()).attr("offendingFile", e.offendingFile()).attr("errorRows", e.errorRows());
        }
        this.eventLog.emit(b);
    }

    /**
     * Wire an embedded {@link AssistAgent} (v3.0, M0). Calls {@link AssistAgent#init(SourceService)}
     * immediately so the agent can subscribe to the bus and capture typed handles <em>before</em>
     * {@link #start()} schedules the first poll. Idempotent-ish: a second registration is ignored
     * with a warning (one agent per service). Normally invoked automatically by {@link #start()}
     * via {@link ServiceLoader}; exposed publicly so tests (and embedders) can supply a provider
     * directly.
     *
     * @param a the agent provider; {@code null} is a no-op
     */
    public synchronized void registerAgent(AssistAgent a) {
        if (a == null) return;
        if (agent != null) {
            log.warn("Assist agent '{}' already registered; ignoring '{}'", agent.name(), a.name());
            return;
        }
        a.init(this);     // init before publishing the reference, so assistAgent() never sees a half-wired agent
        agent = a;
        log.info("Assist agent registered: {}", a.name());
    }

    /** The embedded assist agent, or empty when none is registered/discovered (v3.0). */
    public Optional<AssistAgent> assistAgent() {
        return Optional.ofNullable(agent);
    }

    /** Report recovery state, wire enrichment, then schedule the recurring poll cycle. */
    public void start() {
        // v3.0 (M0): discover an optional embedded assist agent on the classpath and wire it
        // in before the bus gets its first event. No-op when the agent module is absent (no
        // ServiceLoader provider) or one was already registered explicitly.
        if (agent == null) {
            ServiceLoader.load(AssistAgent.class).findFirst().ifPresent(this::registerAgent);
        }
        for (ConfigRegistry.Entry e : configRegistry.all()) {
            int committed = fileStatus.committedBatches(e.config()).size();   // on-disk truth
            log.info("Registered '{}' ({}) — {} previously committed batch(es)",
                    e.id(), e.path(), committed);
        }
        // Project the on-disk audit into the status DB (if DB-backed) before serving any
        // query, so the API/observability see current state from the first scrape onward.
        syncStatus();
        // Metrics subscribes to the bus and registers scrape collectors; enrichment
        // subscribes + schedules its completeness jobs. Both wire up before the first
        // poll cycle so no commit event from that cycle is missed.
        metrics.start();
        if (enrichment != null) enrichment.start();
        if (jobs != null) jobs.start();
        if (agent != null) {
            try { agent.start(); }
            catch (Exception e) { log.warn("Assist agent '{}' start failed: {}", agent.name(), e.getMessage()); }
        }
        // T13 / §3.8 — drive event-triggered flows: an upstream batch-commit signals the downstream
        // flow's coalescer (off the publishing thread, see triggerWorkers). Subscribed before the first
        // poll cycle so no commit is missed; flows with no event trigger ignore every event.
        bus.subscribe(this::onUpstreamCommit);
        scheduler.everySeconds("poll-all", 0, pollSeconds, this::runAllOnce);
        // SLA sweep (Phase 3, v4.4.0): periodically breach overdue, unresolved ISSUEs — each new breach
        // emits an OBJECT_SLA_BREACH event. Always scheduled (a cheap no-op when there are no issues);
        // -Dobjects.sla.sweep.seconds sets the cadence (default 60), <=0 disables it.
        long slaSweepSeconds = Long.getLong("objects.sla.sweep.seconds", 60L);
        if (slaSweepSeconds > 0)
            scheduler.everySeconds("sla-sweep", slaSweepSeconds, slaSweepSeconds,
                    () -> objects.sweepIssueSla(System.currentTimeMillis()));
        log.info("SourceService started: {} pipeline(s), poll every {}s, up to {} concurrent run(s)",
                registry.size(), pollSeconds, maxConcurrentRuns);
        this.eventLog.emit(Event.builder(EventType.SERVICE_STARTED)
                .source(SourceService.class.getName())
                .message("SourceService started")
                .attr("pipelines", registry.size())
                .attr("pollSeconds", pollSeconds)
                .attr("maxConcurrentRuns", maxConcurrentRuns));
    }

    /**
     * Run every registered pipeline once, concurrently (bounded by the global budget),
     * feeding committed-batch events to the bus. Public so tests and operators can
     * trigger a single cycle deterministically.
     *
     * @return the run outcome (total / failed source counts)
     */
    public MultiSourceProcessor.RunResult runAllOnce() {
        ingestLock.lock();   // never overlap with another cycle / operator trigger (see field doc)
        try {
            // Re-index configs once per cycle — now an mtime-cached rebuild, so a steady-state cycle
            // re-parses nothing (and re-reads no schema files); an edited pipeline/schema reloads on the
            // next tick. Fires catalog invalidation via the registry callback.
            configRegistry.rebuild(registry);
            // Build the run set from the cached index: skip paused pipelines and any not yet activated
            // (`active: true`). Each runnable config is re-stamped with a fresh run timestamp for this
            // cycle (cheap copy; no re-parse), so every cycle still gets its own status/batch/lineage CSVs.
            // Iterate the registered paths (not the id-keyed index) so two files are both run even if
            // they declare the same name — matching the prior path-level run semantics.
            long nowMs = System.currentTimeMillis();
            List<PipelineConfig> toRun = new ArrayList<>();
            List<String> activeNames  = new ArrayList<>();
            for (Path p : registry) {
                PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
                if (cfg == null) continue;                                   // unloadable — already warned
                String id = cfg.identity().pipelineName();
                if (paused.contains(id) || !cfg.active()) continue;          // paused or not activated
                if (!dueThisTick(cfg, id, nowMs)) continue;                  // T13: trigger gates the loop
                toRun.add(cfg.forNewRun());                                  // fresh per-cycle timestamp
                activeNames.add(id);
            }
            if (toRun.isEmpty()) return new MultiSourceProcessor.RunResult(0, 0);
            activeNames.forEach(id -> lastRunAtMs.put(id, nowMs));           // stamp cadence baseline (start-to-start)
            com.gamma.metrics.MetricRegistry reg = com.gamma.metrics.MetricRegistry.global();
            reg.inc("inspecto_poll_cycles_total", "Poll cycles run", Map.of());
            reg.setGauge("inspecto_active_runs", "Source runs currently executing", Map.of(), toRun.size());
            running.addAll(activeNames);
            MultiSourceProcessor.RunResult r;
            try {
                r = MultiSourceProcessor.runConfigs(toRun, maxConcurrentRuns, bus.sink());
                if (r.failed() > 0) {
                    log.warn("Poll cycle: {} of {} source(s) failed", r.failed(), r.total());
                    reg.inc("inspecto_source_run_failures_total", "Source-run failures", Map.of(), r.failed());
                }
            } finally {
                running.removeAll(activeNames);
                reg.setGauge("inspecto_active_runs", "Source runs currently executing", Map.of(), 0);
            }
            // Refresh the status DB (if DB-backed) so this cycle's commits are queryable.
            syncStatus();
            return r;
        } finally {
            // (Catalog invalidation already fired from configRegistry.rebuild at the top of the cycle.)
            ingestLock.unlock();
        }
    }

    /**
     * Whether the loop scheduler should run {@code cfg} on this tick, per its entry-node trigger (T13 / §3.8):
     * <ul>
     *   <li><b>absent / {@code DEFAULT_POLL}</b> — every tick (unchanged from the pre-T13 poll-all behaviour);</li>
     *   <li><b>{@code schedule:{every:N}}</b> — only once {@code N} has elapsed since the last run;</li>
     *   <li><b>{@code schedule:{cron}}</b> — only when a cron fire is due since the last run;</li>
     *   <li><b>{@code event} / {@code manual}</b> — never on the loop; driven by {@link #onUpstreamCommit}
     *       and {@link #runPipeline} respectively.</li>
     * </ul>
     */
    private boolean dueThisTick(PipelineConfig cfg, String id, long nowMs) {
        FlowTrigger t = FlowTrigger.of(cfg.triggerConfig());
        return switch (t.scheduler()) {
            case EVENT, MANUAL -> false;                       // driven off the poll loop
            case LOOP -> switch (t.kind()) {
                case SCHEDULE_INTERVAL -> {
                    Long last = lastRunAtMs.get(id);
                    yield last == null || (nowMs - last) >= t.everyMs();
                }
                case SCHEDULE_CRON -> cronDue(id, t.cron(), nowMs);
                default -> true;                               // DEFAULT_POLL — every tick (today's behaviour)
            };
        };
    }

    /** A cron trigger is due when its next fire after the last run (or service start) is at/​before now. */
    private boolean cronDue(String id, String cron, long nowMs) {
        try {
            long lastMs = lastRunAtMs.getOrDefault(id, serviceStartMs);
            ZonedDateTime from = Instant.ofEpochMilli(lastMs).atZone(triggerZone);
            ZonedDateTime next = CronExpression.parse(cron).next(from);
            return !next.isAfter(Instant.ofEpochMilli(nowMs).atZone(triggerZone));   // next <= now ⇒ fire due
        } catch (Exception e) {
            log.warn("Pipeline '{}' has an invalid cron trigger '{}' — skipping this cycle: {}",
                    id, cron, e.getMessage());
            return false;
        }
    }

    /**
     * Bus listener (T13 / §3.8): an upstream SUCCESS commit triggers every {@code event}-triggered flow whose
     * {@code from} names that upstream. The run is handed to {@link #triggerWorkers} (the bus delivers on the
     * publishing thread under {@link #ingestLock}; running inline would deadlock) and coalesced per flow so an
     * upstream storm collapses to one non-overlapping run.
     */
    private void onUpstreamCommit(BatchEvent event) {
        if (!"SUCCESS".equals(event.status())) return;
        for (Path p : registry) {
            PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
            if (cfg == null) continue;
            String id = cfg.identity().pipelineName();
            if (paused.contains(id) || !cfg.active()) continue;
            if (id.equals(event.pipeline())) continue;                       // self-loop guard
            FlowTrigger t = FlowTrigger.of(cfg.triggerConfig());
            if (t.scheduler() != FlowTrigger.Scheduler.EVENT) continue;
            if (!triggerMatches(t.from(), event.pipeline())) continue;
            TriggerCoalescer coalescer = eventCoalescers.computeIfAbsent(id, k -> new TriggerCoalescer());
            triggerWorkers.submit(() -> coalescer.signal(() -> runPipeline(id)));
        }
    }

    /**
     * Whether an event trigger's {@code from} names {@code upstream}. Matches case-insensitively against the
     * emitted pipeline id (lowercased {@code pipelineName}) and tolerates a {@code flows/<id>} prefix, so an
     * operator may write {@code from: orders}, {@code from: ORDERS}, or {@code from: flows/orders}.
     */
    private static boolean triggerMatches(String from, String upstream) {
        if (from == null || from.isBlank() || upstream == null) return false;
        String f = from.trim().toLowerCase();
        String u = upstream.toLowerCase();
        return f.equals(u) || f.endsWith("/" + u);
    }

    /**
     * Deletion fence (T25, §3.8 rule 4): given the data stores a delete/maintenance job is about to remove,
     * report which are <em>currently</em> produced or consumed by a running flow — the one cross-driver
     * hazard — and surface each as a {@code STORE_DELETE_CONFLICT} event/alert. Lifts the configured
     * pipelines and intersects {@link DeletionFence#check} with the live {@link #running} set. Non-blocking:
     * it warns/alerts; the operator fences via a quiet window or slice-disjoint deletes. Returns the
     * conflicts (empty = clear).
     */
    public List<DeletionFence.Conflict> checkDeletion(java.util.Collection<String> targetStores) {
        if (targetStores == null || targetStores.isEmpty()) return List.of();
        List<FlowGraph> flows = new ArrayList<>();
        for (Path p : registry) configRegistry.configForPath(p).ifPresent(c -> flows.add(PipelineLift.lift(c)));
        // T32: authored flow jobs also produce/consume stores. Include them in the topology and union their
        // in-flight runs into the active set, so a delete that races an active flow-job reader/writer is
        // flagged as a conflict — not just one racing a running pipeline.
        Set<String> active = running;
        if (flowStore != null) {
            try {
                flows.addAll(flowStore.list());
            } catch (RuntimeException e) {
                log.warn("Deletion fence: could not list authored flows ({}): {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
            if (jobs != null && !jobs.runningFlows().isEmpty()) {
                active = new java.util.LinkedHashSet<>(running);
                active.addAll(jobs.runningFlows());
            }
        }
        List<DeletionFence.Conflict> conflicts = DeletionFence.check(targetStores, flows, active);
        for (DeletionFence.Conflict c : conflicts) {
            log.warn("Deletion fence: store '{}' has an active reader/writer — producers={}, consumers={}",
                    c.store(), c.activeProducers(), c.activeConsumers());
            this.eventLog.emit(Event.builder(EventType.STORE_DELETE_CONFLICT)
                    .source(SourceService.class.getName())
                    .message("Delete of store '" + c.store() + "' races an active flow")
                    .attr("store", c.store())
                    .attr("activeProducers", String.join(",", c.activeProducers()))
                    .attr("activeConsumers", String.join(",", c.activeConsumers())));
        }
        return conflicts;
    }

    /**
     * Register a new pipeline from an on-disk {@code .toon} at runtime (v4.1.0). The file must load
     * as a valid {@link PipelineConfig}; its in-file {@code name} becomes the pipeline id. The path is
     * added to the active registry and indexed immediately (so the catalog and {@link #pipelines()}
     * reflect it at once); the next poll cycle then processes it with no restart — the single
     * poll-all cycle already fans out over the whole registry, so no extra scheduling is needed.
     *
     * <p>Idempotent on path: re-registering the same file returns its existing id. A <em>different</em>
     * file whose in-file name collides with an already-registered pipeline is rejected, so registration
     * never silently shadows a running pipeline.
     *
     * @param path the config file to register
     * @return the registered pipeline id (its in-file {@code name})
     * @throws IllegalArgumentException if the file does not load as a valid pipeline
     * @throws IllegalStateException    if its id collides with a different registered pipeline
     */
    public synchronized String registerPipeline(Path path) {
        Path norm = path.toAbsolutePath().normalize();
        PipelineConfig cfg;
        try {
            cfg = PipelineConfig.load(norm.toString());   // structural validation
        } catch (java.io.IOException io) {
            throw new IllegalArgumentException("cannot read config " + norm + ": " + io.getMessage(), io);
        }
        String id = cfg.identity().pipelineName();
        Optional<Path> existing = configRegistry.getPath(id);
        if (existing.isPresent()) {
            if (existing.get().toAbsolutePath().normalize().equals(norm)) return id;   // already registered
            throw new IllegalStateException("pipeline id '" + id + "' is already registered from "
                    + existing.get());
        }
        ingestLock.lock();   // serialise the registry mutation against a running poll cycle
        try {
            registry.add(norm);
            configRegistry.rebuild(registry);   // refresh the read surface now; fires catalog invalidation
        } finally {
            ingestLock.unlock();
        }
        log.info("Registered pipeline '{}' from {} ({} pipeline(s) now active)", id, norm, registry.size());
        this.eventLog.emit(Event.builder(EventType.PIPELINE_REGISTERED)
                .source(SourceService.class.getName()).pipeline(id)
                .message("Pipeline registered: " + id)
                .attr("configPath", norm.toString()).attr("activePipelines", registry.size()));
        return id;
    }

    /**
     * If the status store is DB-backed, project the latest on-disk audit into it. No-op
     * for the file backend. Failures are logged, never fatal — the on-disk audit (and the
     * file store) remain the durable source of truth, so a transient DB hiccup only makes
     * the DB momentarily stale, it never loses or blocks ingest.
     */
    private void syncStatus() {
        if (!(status instanceof DbStatusStore db)) return;
        try {
            db.sync(fileStatus, loadConfigs());
        } catch (Exception e) {
            log.warn("Status DB sync failed (DB may be momentarily stale): {}", e.getMessage());
        }
    }

    /** Every successfully-indexed config (from the registry; unloadable ones were warned at rebuild). */
    private List<PipelineConfig> loadConfigs() {
        return configRegistry.configs();
    }

    // ── Control API surface (M3) ─────────────────────────────────────────────────

    /** The status store backing query endpoints (file-backed today, DB-backed in M5). */
    public StatusStore statusStore() {
        return status;
    }

    /** The report aggregator backing the Control API's status / batch-audit report endpoints. */
    public ReportService reports() {
        return reports;
    }

    /** The config-driven job registry, or empty when no jobs are registered (v2.8.0). */
    public Optional<JobService> jobService() {
        return Optional.ofNullable(jobs);
    }

    /** The Stage-2 enrichment service, or empty when no enrichment jobs are registered (v2.9.0). */
    public Optional<EnrichmentService> enrichmentService() {
        return Optional.ofNullable(enrichment);
    }

    /** The metadata graph / data catalog (M2, v3.2.0): always present (core, zero-AI by default). */
    public MetadataGraphService catalog() {
        return catalog;
    }

    /** The read-only config seam (pipelines + enrichments + semantics) — handed to the assist agent
     *  (M8, v3.8.0) so {@code report-sql} can resolve a pipeline/job name to its config. */
    public ConfigSource configSource() {
        return configSource;
    }

    /**
     * Inbox status for a pipeline (M3, file-processing visibility): how many inbox files are waiting
     * to be processed and whether the pipeline is currently ingesting.
     *
     * @param pipeline name (normalised)
     * @param inbox    absolute poll-root path the files are scanned from
     * @param pending  files matching {@code processing.file_pattern} not yet processed (the candidate
     *                 set a poll cycle would pick up); {@code -1} if the scan failed
     * @param running  whether this pipeline is mid-ingest right now ("under processing")
     * @param current  the file being ingested right now ("file index of total"); {@code null}
     *                 when the pipeline is not mid-file (v4.1.0, per-file in-flight visibility)
     */
    public record InboxStatus(String pipeline, String inbox, int pending, boolean running,
                              IngestProgress.Snapshot current) {}

    /** Inbox/processing status for one registered pipeline; empty if no pipeline by that name. */
    public Optional<InboxStatus> inboxStatus(String pipelineName) {
        return configFor(pipelineName).map(cfg -> new InboxStatus(
                pipelineName,
                java.nio.file.Paths.get(cfg.dirs().poll()).toAbsolutePath().toString(),
                SourceProcessor.countPending(cfg),
                running.contains(pipelineName),
                IngestProgress.current(cfg.identity().pipelineName())));
    }

    /** List each registered pipeline with its current paused state and commit count. */
    public List<PipelineView> pipelines() {
        List<PipelineView> out = new ArrayList<>();
        for (ConfigRegistry.Entry e : configRegistry.all()) {
            out.add(new PipelineView(e.id(), e.path().toString(),
                    paused.contains(e.id()), status.committedBatches(e.config()).size()));
        }
        return out;
    }

    /** The {@link PipelineConfig} for a registered pipeline by its (normalised) name — O(1). */
    public Optional<PipelineConfig> configFor(String pipelineName) {
        return configRegistry.get(pipelineName);
    }

    /**
     * Flatten each registered pipeline's source acquisition config for the Acquisition/Sources UI
     * ({@code GET /sources}). Pure config read (no I/O) plus, for a {@code db} source bound to a connection,
     * the current row-level DB watermark derived from the acquisition ledger.
     */
    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConfigRegistry.Entry e : configRegistry.all()) {
            PipelineConfig.Source s = e.config().source();
            if (s == null) continue;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("pipeline", e.id());
            m.put("id", s.id());
            m.put("connector", s.connector());
            m.put("connection", s.connection());
            m.put("includes", s.includes());
            m.put("excludes", s.excludes());
            m.put("recursiveDepth", s.recursiveDepth());
            m.put("duplicateMode", s.duplicate().mode());
            m.put("duplicateOnChange", s.duplicate().onChange());
            m.put("guarantee", s.guarantee().name());
            m.put("incrementalWatermark", s.incremental().watermark());   // null when disabled
            m.put("fetchParallel", s.fetch().parallelFetch());
            m.put("fetchRateLimit", s.fetch().rateLimitBytesPerSec());
            m.put("postAction", s.postAction().onSuccess());
            // Row-level DB watermark (db sources only): keyed by the bound connection-profile id.
            String dbWatermark = null;
            if (s.hasConnection() && "db".equals(s.connector()))
                dbWatermark = com.gamma.acquire.AcquisitionLedgers.shared().dbWatermark(s.connection()).orElse(null);
            m.put("dbWatermarkCurrent", dbWatermark);
            out.add(m);
        }
        return out;
    }

    /** Whether any registered pipeline's source binds to this connection id (blocks a UI delete). */
    public boolean connectionInUse(String id) {
        if (id == null) return false;
        String t = id.trim();
        for (ConfigRegistry.Entry e : configRegistry.all()) {
            PipelineConfig.Source s = e.config().source();
            if (s != null && t.equals(s.connection())) return true;
        }
        return false;
    }

    /** Drop a connection profile from the in-memory registries (UI delete); idempotent. */
    public void unregisterConnection(String id) {
        if (id != null) {
            connections.remove(id.trim());
            com.gamma.acquire.ConnectionRegistry.remove(id);
        }
    }

    /** The registry path of a pipeline by name, if registered — O(1). */
    public Optional<Path> pathFor(String pipelineName) {
        return configRegistry.getPath(pipelineName);
    }

    /** Run a single registered pipeline once. Empty if no pipeline by that name. */
    public Optional<MultiSourceProcessor.RunResult> runPipeline(String pipelineName) {
        return pathFor(pipelineName).map(p -> {
            ingestLock.lock();   // serialize with the poll cycle / other triggers (see field doc)
            running.add(pipelineName);
            try {
                lastRunAtMs.put(pipelineName, System.currentTimeMillis());   // T13: any run resets the cadence
                return MultiSourceProcessor.runAll(List.of(p), 1, bus.sink());
            } finally {
                running.remove(pipelineName);
                ingestLock.unlock();
            }
        });
    }

    /** Pause a pipeline (the poll cycle skips it). Returns false if not registered. */
    public boolean pause(String pipelineName) {
        if (pathFor(pipelineName).isEmpty()) return false;
        paused.add(pipelineName);
        log.info("Pipeline '{}' paused", pipelineName);
        this.eventLog.emit(Event.builder(EventType.PIPELINE_PAUSED)
                .source(SourceService.class.getName()).pipeline(pipelineName)
                .message("Pipeline paused: " + pipelineName));
        return true;
    }

    /** Resume a paused pipeline. Returns false if not registered. */
    public boolean resume(String pipelineName) {
        if (pathFor(pipelineName).isEmpty()) return false;
        paused.remove(pipelineName);
        log.info("Pipeline '{}' resumed", pipelineName);
        this.eventLog.emit(Event.builder(EventType.PIPELINE_RESUMED)
                .source(SourceService.class.getName()).pipeline(pipelineName)
                .message("Pipeline resumed: " + pipelineName));
        return true;
    }

    @Override
    public void close() {
        if (agent != null) {                           // release agent resources first
            try { agent.close(); }
            catch (Exception e) { log.warn("Error closing assist agent '{}': {}", agent.name(), e.getMessage()); }
        }
        if (jobs != null) jobs.close();               // drain in-flight job runs first
        triggerWorkers.close();                        // drain in-flight event-triggered flow runs (T13)
        if (enrichment != null) enrichment.close();   // drain in-flight recomputes first
        this.eventLog.removeSubscriber(eventObjectBridge);   // de-register the D2 gap→ALERT bridge
        EventLog.unregister(spaceId);                  // stop MDC-routing to this space's log
        scheduler.close();
        if (status instanceof AutoCloseable c) {       // close a DB-backed store's connection
            try { c.close(); } catch (Exception e) { log.warn("Error closing status store: {}", e.getMessage()); }
        }
        try { objectStore.close(); } catch (Exception e) { log.warn("Error closing object store: {}", e.getMessage()); }
        try { linkStore.close(); } catch (Exception e) { log.warn("Error closing link store: {}", e.getMessage()); }
        try { noteStore.close(); } catch (Exception e) { log.warn("Error closing note store: {}", e.getMessage()); }
        log.info("SourceService stopped");
        // Close last so the "stopped" log line above is itself captured, then flushed to disk.
        try { events.close(); } catch (Exception e) { log.warn("Error closing event store: {}", e.getMessage()); }
    }

    // ── CLI ────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceService [-Dservice.poll.seconds=N] "
                    + "[-Dservice.max.runs=M] <pipeline.toon | dir> [more ...]");
            System.exit(1);
        }
        SourceService svc = fromArgs(args);
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            svc.close();
            latch.countDown();
        }, "inspecto-shutdown"));
        svc.start();
        latch.await();   // block until SIGTERM/SIGINT triggers the shutdown hook
    }

    /**
     * Build a fully-wired service from CLI-style args. Delegates to {@link ServiceBootstrap#build}
     * (the config-discovery/parsing concern). Shared by the service and Control API entry points.
     */
    public static SourceService fromArgs(String[] args) throws IOException {
        return ServiceBootstrap.build(args);
    }
}
