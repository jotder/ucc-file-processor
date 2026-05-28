package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.LogSetup;

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

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceProcessor <pipeline_config_path>");
            System.exit(1);
        }
        PipelineConfig cfg = PipelineConfig.load(args[0]);
        LogSetup.configure(cfg.logDir, cfg.pipelineName, cfg.runTimestamp);
        run(cfg);
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
            System.out.println("No new files to process in " + root);
            return;
        }

        // ── plan batches ─────────────────────────────────────────────────────────
        BatchPlanner.SchemaResolver resolver = (cfg.schemaSelector != null)
                ? cfg.schemaSelector::select
                : f -> new SchemaSelector.Selection(cfg.singleSchema, null);

        List<Batch> batches = BatchPlanner.plan(
                candidates, resolver, cfg.batchMaxFiles, cfg.batchMaxBytes, cfg.runTimestamp);
        System.out.printf("Planned %d batch(es) from %d file(s) using %d thread(s)...%n",
                batches.size(), candidates.size(), cfg.threads);

        // ── process batches in parallel ────────────────────────────────────────
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath);

        ExecutorService executor = Executors.newFixedThreadPool(cfg.threads);
        List<Future<?>> futures  = new ArrayList<>();
        for (Batch b : batches)
            futures.add(executor.submit(() -> BatchProcessor.process(b, cfg, audit)));

        for (Future<?> f : futures) {
            try   { f.get(); }
            catch (Exception e) { e.printStackTrace(); }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
    }
}
