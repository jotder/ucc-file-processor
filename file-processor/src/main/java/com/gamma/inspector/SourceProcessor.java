package com.gamma.inspector;

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
public class SourceProcessor {

    private static final Logger log = LoggerFactory.getLogger(SourceProcessor.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceProcessor <pipeline_config_path>");
            System.exit(1);
        }
        PipelineConfig cfg = PipelineConfig.load(args[0]);
        LogSetup.configure(cfg.logDir, cfg.pipelineName, cfg.runTimestamp);
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
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(cfg.filePattern);
        Path root           = Paths.get(cfg.pollDir).toAbsolutePath();
        Path errorsDir      = Paths.get(cfg.errorsDir).toAbsolutePath();
        Path quarantineDir  = Paths.get(cfg.quarantineDir).toAbsolutePath();
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
        BatchPlanner.SchemaResolver resolver = (cfg.schemaSelector != null)
                ? cfg.schemaSelector::select
                : f -> new SchemaSelector.Selection(cfg.singleSchema, null);

        List<Batch> batches = BatchPlanner.plan(
                candidates, resolver, cfg.batchMaxFiles, cfg.batchMaxBytes, cfg.runTimestamp);
        log.info("Planned {} batch(es) from {} file(s) using {} thread(s)...",
                batches.size(), candidates.size(), cfg.threads);

        // ── process batches in parallel ────────────────────────────────────────
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath);

        ExecutorService executor = Executors.newFixedThreadPool(cfg.threads);
        List<Future<?>> futures  = new ArrayList<>();
        for (Batch b : batches)
            futures.add(executor.submit(() -> BatchProcessor.process(b, cfg, audit)));

        // f.get() blocks until each future completes; no additional awaitTermination needed.
        // Track failures so main() can surface a non-zero exit code on partial-failure runs.
        int failedBatches = 0;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                failedBatches++;
                log.error("Batch processing failed", e);
            }
        }
        executor.shutdown();
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
