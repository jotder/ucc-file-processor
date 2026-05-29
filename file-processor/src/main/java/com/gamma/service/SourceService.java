package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.MultiSourceProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
    private final StatusStore status = new FileStatusStore();
    private final Scheduler scheduler = new Scheduler();
    private final EnrichmentService enrichment;

    public SourceService(List<Path> registry, long pollSeconds, int maxConcurrentRuns) {
        this(registry, List.of(), pollSeconds, maxConcurrentRuns);
    }

    public SourceService(List<Path> registry, List<EnrichmentConfig> enrichJobs,
                         long pollSeconds, int maxConcurrentRuns) {
        this.registry          = List.copyOf(registry);
        this.pollSeconds       = Math.max(1, pollSeconds);
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.enrichment        = enrichJobs.isEmpty()
                ? null
                : new EnrichmentService(enrichJobs, bus, scheduler);
    }

    /** The bus carrying committed-batch events; subscribe before {@link #start()}. */
    public BatchEventBus eventBus() {
        return bus;
    }

    /** Report recovery state, wire enrichment, then schedule the recurring poll cycle. */
    public void start() {
        for (Path p : registry) {
            try {
                PipelineConfig cfg = PipelineConfig.load(p.toString());
                int committed = status.committedBatches(cfg).size();
                log.info("Registered '{}' ({}) — {} previously committed batch(es)",
                        cfg.identity().pipelineName(), p, committed);
            } catch (Exception e) {
                log.warn("Could not load config {} at startup: {}", p, e.getMessage());
            }
        }
        // Enrichment subscribes to the bus and schedules its completeness jobs before the
        // first poll cycle, so it never misses a commit event from that cycle.
        if (enrichment != null) enrichment.start();
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
        MultiSourceProcessor.RunResult r =
                MultiSourceProcessor.runAll(registry, maxConcurrentRuns, bus.sink());
        if (r.failed() > 0)
            log.warn("Poll cycle: {} of {} source(s) failed", r.failed(), r.total());
        return r;
    }

    @Override
    public void close() {
        if (enrichment != null) enrichment.close();   // drain in-flight recomputes first
        scheduler.close();
        log.info("SourceService stopped");
    }

    // ── CLI ────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceService [-Dservice.poll.seconds=N] "
                    + "[-Dservice.max.runs=M] <pipeline.toon | dir> [more ...]");
            System.exit(1);
        }
        List<Path> registry = MultiSourceProcessor.resolveConfigs(args);
        if (registry.isEmpty()) {
            System.err.println("No *_pipeline.toon files found in: " + String.join(", ", args));
            System.exit(1);
        }
        List<EnrichmentConfig> enrichJobs = loadEnrichJobs(resolveBySuffix(args, "_enrich.toon"));

        long pollSeconds = Long.getLong("service.poll.seconds", 60L);
        int  maxRuns     = Integer.getInteger("service.max.runs", Math.max(1, registry.size()));

        SourceService svc = new SourceService(registry, enrichJobs, pollSeconds, maxRuns);
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            svc.close();
            latch.countDown();
        }, "ucc-shutdown"));
        svc.start();
        latch.await();   // block until SIGTERM/SIGINT triggers the shutdown hook
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
}
