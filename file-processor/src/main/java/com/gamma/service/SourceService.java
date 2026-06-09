package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.catalog.CatalogOverlay;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.MultiSourceProcessor;
import com.gamma.inspector.SourceProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

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

    private final List<Path> registry;
    private final long pollSeconds;
    private final int  maxConcurrentRuns;
    private final BatchEventBus bus = new BatchEventBus();
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
    /** Serializes ingest cycles so an operator-triggered run (Control API {@code /trigger},
     *  {@code /pipelines/{name}/trigger}) can never overlap the scheduled poll cycle or another
     *  trigger. The scheduler is already non-overlapping (fixed-delay); this guards the
     *  cross-entrypoint case. A waiting caller re-evaluates the inbox after acquiring the lock,
     *  by which time the prior cycle has written its {@code .processed} markers — so
     *  already-ingested files are skipped rather than double-processed. */
    private final ReentrantLock ingestLock = new ReentrantLock();
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
        this.registry          = List.copyOf(registry);
        this.pollSeconds       = Math.max(1, pollSeconds);
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.status            = statusStore != null ? statusStore : fileStatus;
        this.enrichment        = enrichJobs.isEmpty()
                ? null
                : new EnrichmentService(enrichJobs, bus, scheduler);
        this.jobs              = jobConfigs.isEmpty()
                ? null
                : new JobService(jobConfigs, bus, scheduler, reports,
                        System.getProperty("jobs.audit.dir", "jobs_audit"));
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

    /** The bus carrying committed-batch events; subscribe before {@link #start()}. */
    public BatchEventBus eventBus() {
        return bus;
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
        scheduler.everySeconds("poll-all", 0, pollSeconds, this::runAllOnce);
        log.info("SourceService started: {} pipeline(s), poll every {}s, up to {} concurrent run(s)",
                registry.size(), pollSeconds, maxConcurrentRuns);
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
            // Re-index configs once per cycle (picks up edits/additions; fires catalog invalidation
            // via the registry callback), replacing the former per-call O(n) re-parse scans.
            configRegistry.rebuild(registry);
            List<Path> active = paused.isEmpty() ? registry : activeRegistry();
            if (active.isEmpty()) return new MultiSourceProcessor.RunResult(0, 0);
            com.gamma.metrics.MetricRegistry reg = com.gamma.metrics.MetricRegistry.global();
            reg.inc("ucc_poll_cycles_total", "Poll cycles run", Map.of());
            reg.setGauge("ucc_active_runs", "Source runs currently executing", Map.of(), active.size());
            List<String> activeNames = activeNames(active);
            running.addAll(activeNames);
            MultiSourceProcessor.RunResult r;
            try {
                r = MultiSourceProcessor.runAll(active, maxConcurrentRuns, bus.sink());
                if (r.failed() > 0) {
                    log.warn("Poll cycle: {} of {} source(s) failed", r.failed(), r.total());
                    reg.inc("ucc_source_run_failures_total", "Source-run failures", Map.of(), r.failed());
                }
            } finally {
                running.removeAll(activeNames);
                reg.setGauge("ucc_active_runs", "Source runs currently executing", Map.of(), 0);
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
     */
    public record InboxStatus(String pipeline, String inbox, int pending, boolean running) {}

    /** Inbox/processing status for one registered pipeline; empty if no pipeline by that name. */
    public Optional<InboxStatus> inboxStatus(String pipelineName) {
        return configFor(pipelineName).map(cfg -> new InboxStatus(
                pipelineName,
                java.nio.file.Paths.get(cfg.dirs().poll()).toAbsolutePath().toString(),
                SourceProcessor.countPending(cfg),
                running.contains(pipelineName)));
    }

    /** The pipeline names for a set of active registry paths (skips paths with no indexed identity). */
    private List<String> activeNames(List<Path> active) {
        List<String> names = new ArrayList<>();
        for (Path p : active) configRegistry.idForPath(p).ifPresent(names::add);
        return names;
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
        return true;
    }

    /** Resume a paused pipeline. Returns false if not registered. */
    public boolean resume(String pipelineName) {
        if (pathFor(pipelineName).isEmpty()) return false;
        paused.remove(pipelineName);
        log.info("Pipeline '{}' resumed", pipelineName);
        return true;
    }

    /** Registry paths whose pipeline is not currently paused (resolved via the O(1) index;
     *  a path with no indexed identity — i.e. unloadable — is kept rather than silently dropped). */
    private List<Path> activeRegistry() {
        List<Path> active = new ArrayList<>();
        for (Path p : registry) {
            String id = configRegistry.idForPath(p).orElse(null);
            if (id == null || !paused.contains(id)) {
                active.add(p);
            }
        }
        return active;
    }

    @Override
    public void close() {
        if (agent != null) {                           // release agent resources first
            try { agent.close(); }
            catch (Exception e) { log.warn("Error closing assist agent '{}': {}", agent.name(), e.getMessage()); }
        }
        if (jobs != null) jobs.close();               // drain in-flight job runs first
        if (enrichment != null) enrichment.close();   // drain in-flight recomputes first
        scheduler.close();
        if (status instanceof AutoCloseable c) {       // close a DB-backed store's connection
            try { c.close(); } catch (Exception e) { log.warn("Error closing status store: {}", e.getMessage()); }
        }
        log.info("SourceService stopped");
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
        }, "ucc-shutdown"));
        svc.start();
        latch.await();   // block until SIGTERM/SIGINT triggers the shutdown hook
    }

    /**
     * Build a fully-wired service from CLI-style args: each path (file or dir) is
     * scanned for {@code *_pipeline.toon} (Stage-1 sources) and {@code *_enrich.toon}
     * (Stage-2 jobs). Reads {@code -Dservice.poll.seconds} (default 60) and
     * {@code -Dservice.max.runs} (default = source count). Shared by the service and
     * Control API entry points. Exits the JVM with a message if no sources are found.
     */
    public static SourceService fromArgs(String[] args) throws IOException {
        List<Path> registry = MultiSourceProcessor.resolveConfigs(args);
        List<EnrichmentConfig> enrichJobs = loadEnrichJobs(resolveBySuffix(args, "_enrich.toon"));
        List<JobConfig> jobConfigs = loadJobs(resolveBySuffix(args, "_job.toon"));
        List<SemanticModel> semantics = loadSemantics(resolveBySuffix(args, "_meta.toon"));
        if (registry.isEmpty() && enrichJobs.isEmpty() && jobConfigs.isEmpty()) {
            System.err.println("No *_pipeline.toon / *_enrich.toon / *_job.toon files found in: "
                    + String.join(", ", args));
            System.exit(1);
        }
        long pollSeconds = Long.getLong("service.poll.seconds", 60L);
        int  maxRuns     = Integer.getInteger("service.max.runs", Math.max(1, registry.size()));
        return new SourceService(registry, enrichJobs, jobConfigs, semantics, pollSeconds, maxRuns, buildStatusStore());
    }

    /** Default DuckDB status database file when {@code status.backend=db} and no URL is given. */
    private static final String DEFAULT_DB_URL = "jdbc:duckdb:ucc-status.db";

    /**
     * Select the status backend from system properties (M5):
     * {@code -Dstatus.backend=file} (default) reads the on-disk audit directly;
     * {@code -Dstatus.backend=db} projects it into a database. The DB engine is chosen by
     * {@code -Dstatus.db.url} and defaults to a local <b>DuckDB</b> file
     * ({@value #DEFAULT_DB_URL}) — the bundled, zero-extra-dependency primary engine. Point
     * the URL at {@code jdbc:postgresql://…} (with the PG driver on the classpath) for a
     * future distributed deployment; {@code -Dstatus.db.user}/{@code .password} are optional.
     */
    private static StatusStore buildStatusStore() {
        String backend = System.getProperty("status.backend", "file");
        if (!"db".equalsIgnoreCase(backend)) return new FileStatusStore();
        String url = System.getProperty("status.db.url", DEFAULT_DB_URL);
        try {
            StatusStore db = DbStatusStore.open(url,
                    System.getProperty("status.db.user"), System.getProperty("status.db.password"));
            log.info("Status backend: database ({})", url);
            return db;
        } catch (Exception e) {
            throw new IllegalStateException("Could not open status DB at " + url, e);
        }
    }

    /** Walk CLI paths for files ending in {@code suffix} (file args matched directly). */
    private static List<Path> resolveBySuffix(String[] args, String suffix) throws IOException {
        List<Path> out = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a);
            if (Files.isDirectory(p)) {
                try (Stream<Path> w = Files.walk(p)) {
                    w.filter(Files::isRegularFile)
                     .filter(f -> f.getFileName().toString().endsWith(suffix))
                     .sorted().forEach(out::add);
                }
            } else if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(suffix)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Load each enrichment config; a bad one is warned and skipped (others still host). */
    private static List<EnrichmentConfig> loadEnrichJobs(List<Path> paths) {
        List<EnrichmentConfig> jobs = new ArrayList<>();
        for (Path p : paths) {
            try {
                jobs.add(EnrichmentConfig.load(p.toString()));
                log.info("Registered enrichment job from {}", p);
            } catch (Exception e) {
                log.warn("Could not load enrichment config {}: {}", p, e.getMessage());
            }
        }
        return jobs;
    }

    /** Load each {@code *_meta.toon} semantic model; a bad one is warned and skipped. */
    private static List<SemanticModel> loadSemantics(List<Path> paths) {
        List<SemanticModel> models = new ArrayList<>();
        for (Path p : paths) {
            try {
                models.add(SemanticModel.load(p.toString()));
                log.info("Registered semantic model from {}", p);
            } catch (Exception e) {
                log.warn("Could not load semantic model {}: {}", p, e.getMessage());
            }
        }
        return models;
    }

    /** Load each {@code *_job.toon}; a bad one is warned and skipped (others still host). */
    private static List<JobConfig> loadJobs(List<Path> paths) {
        List<JobConfig> jobs = new ArrayList<>();
        for (Path p : paths) {
            try {
                JobConfig c = JobConfig.load(p.toString());
                jobs.add(c);
                log.info("Registered {} job '{}' from {}", c.type(), c.name(), p);
            } catch (Exception e) {
                log.warn("Could not load job config {}: {}", p, e.getMessage());
            }
        }
        return jobs;
    }
}
