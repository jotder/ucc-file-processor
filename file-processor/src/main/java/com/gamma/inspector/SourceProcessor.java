package com.gamma.inspector;

import com.gamma.api.PublicApi;
import com.gamma.etl.*;
import com.gamma.util.LogSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * ETL entry point. Reads a {@code .toon} pipeline config, scans the inbox for
 * matching CSV/CSV.GZ files, groups them into {@link Batch}es by schema (packed
 * to {@code processing.batch.max_files}/{@code max_bytes}), and processes each
 * batch in one pass via {@link BatchProcessor}.
 *
 * <p>A batch of one file is the legacy single-file case and keeps the
 * {@code <basename>_out.<ext>} output name.
 *
 * <p>Run via: {@code java -jar file-processor.jar <pipeline.toon>}
 */
@PublicApi(since = "1.0.0")
public class SourceProcessor {

    private static final Logger log = LoggerFactory.getLogger(SourceProcessor.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceProcessor <pipeline_config_path>");
            System.exit(1);
        }
        PipelineConfig cfg = PipelineConfig.load(args[0]);
        LogSetup.configure(cfg.dirs().logDir(), cfg.identity().pipelineName(), cfg.identity().runTimestamp());
        try {
            run(cfg);
        } catch (BatchProcessingException e) {
            // Partial-failure run: at least one batch threw. We've already logged
            // the per-batch stack traces; exit non-zero so the wrapper script
            // (run.sh / run.bat / cron job) detects the failure without log scraping.
            System.err.println("[FAIL] " + e.getMessage());
            System.exit(2);
        }
    }

    /** Run one poll cycle for {@code cfg}: plan batches and process them in parallel. */
    public static void run(PipelineConfig cfg) throws Exception {
        run(cfg, null);
    }

    /**
     * Run one poll cycle, emitting a {@link BatchEvent} to {@code onCommit} after each
     * SUCCESS batch commits (the service layer passes a bus sink here so downstream
     * stages can react). {@code onCommit} may be {@code null}.
     */
    public static void run(PipelineConfig cfg, java.util.function.Consumer<BatchEvent> onCommit)
            throws Exception {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(cfg.processing().filePattern());
        Path root           = Paths.get(cfg.dirs().poll()).toAbsolutePath();
        Path errorsDir      = Paths.get(cfg.dirs().errors()).toAbsolutePath();
        Path quarantineDir  = Paths.get(cfg.dirs().quarantine()).toAbsolutePath();
        if (!Files.exists(root)) Files.createDirectories(root);

        MarkerManager.cleanupStaleMarkers(cfg);

        // ── collect candidates (skip already-processed) ──────────────────────────
        List<File> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(errorsDir))
                .filter(p -> !p.startsWith(quarantineDir))
                .filter(matcher::matches)
                .map(Path::toFile)
                .filter(f -> !MarkerManager.isAlreadyProcessed(f, cfg))
                .forEach(candidates::add);
        }

        if (candidates.isEmpty()) {
            log.info("No new files to process in {}", root);
            return;
        }

        // ── plan batches ─────────────────────────────────────────────────────────
        BatchPlanner.SchemaResolver resolver = (cfg.schemas().selector() != null)
                ? cfg.schemas().selector()::select
                : f -> new SchemaSelector.Selection(cfg.schemas().single(), null);

        List<Batch> batches = BatchPlanner.plan(
                candidates, resolver, cfg.processing().batchMaxFiles(), cfg.processing().batchMaxBytes(), cfg.identity().runTimestamp());
        log.info("Planned {} batch(es) from {} file(s) using {} thread(s)...",
                batches.size(), candidates.size(), cfg.processing().threads());

        // ── process batches in parallel ────────────────────────────────────────
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath(),
                cfg.dirs().commitLogPath());
        if (onCommit != null) audit.setCommitListener(onCommit);

        // Virtual threads + a Semaphore: a batch blocked on file I/O or DuckDB parks
        // its carrier cheaply instead of pinning a platform thread, but the semaphore
        // bounds how many batches do heavy work at once to cfg.processing().threads(). That gives us
        // the preferred model — virtual-thread concurrency with a controllable cap that
        // protects against I/O pressure and CPU oversubscription. Every batch is
        // submitted up front; all but `permits` of them simply park on acquire().
        int maxConcurrent  = Math.max(1, cfg.processing().threads());
        Semaphore permits  = new Semaphore(maxConcurrent);
        int failedBatches  = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Batch b : batches) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        BatchProcessor.process(b, cfg, audit);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            // f.get() blocks until each batch completes; track failures so main()
            // can surface a non-zero exit code on partial-failure runs.
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    failedBatches++;
                    log.error("Batch processing failed", e);
                }
            }
        }   // try-with-resources close() shuts the executor down and awaits all virtual threads
        if (failedBatches > 0) {
            throw new BatchProcessingException(failedBatches, batches.size());
        }
    }

    /**
     * Thrown by {@link #run(PipelineConfig)} when one or more batches failed.
     * {@link #main(String[])} catches this and exits with a non-zero status so
     * wrapper scripts can detect partial-failure runs without scraping logs.
     */
    public static final class BatchProcessingException extends RuntimeException {
        public final int failedBatches;
        public final int totalBatches;
        BatchProcessingException(int failed, int total) {
            super(failed + " of " + total + " batch(es) failed; see logs for details");
            this.failedBatches = failed;
            this.totalBatches  = total;
        }
    }
}
