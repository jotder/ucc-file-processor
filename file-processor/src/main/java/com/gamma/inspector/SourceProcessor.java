package com.gamma.inspector;

import com.gamma.etl.*;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.LogSetup;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * ETL entry point for the file-processor pipeline.
 *
 * <p>Reads a {@code .toon} pipeline config, scans the configured inbox directory
 * for CSV / CSV.GZ files, and for each unprocessed file:
 * <ol>
 *   <li>Ingests raw rows into a per-worker in-process DuckDB instance ({@link CsvIngester})</li>
 *   <li>Applies type casts and mapping rules ({@link DataTransformer})</li>
 *   <li>Writes partitioned output (CSV or Parquet) to the database directory</li>
 *   <li>Optionally registers output files in a DuckLake catalog ({@link DuckLakeRegistrar})</li>
 *   <li>Creates a marker file to prevent re-processing ({@link MarkerManager})</li>
 *   <li>Moves the source file to backup</li>
 *   <li>Appends an audit row to the run-scoped status CSV ({@link StatusWriter})</li>
 * </ol>
 *
 * <p>All per-file logic is delegated to focused utility classes in
 * {@code com.gamma.etl}.  This class is the thin orchestrator.
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
        pollInbox(cfg);
    }

    // ── inbox polling ─────────────────────────────────────────────────────────

    /**
     * Recursively walks the poll directory, matches files against the configured
     * glob pattern, and submits each match to a fixed thread pool for parallel
     * processing.  The {@code errors/} and {@code quarantine/} sub-directories are
     * excluded to prevent output files from being picked up as input.
     */
    private static void pollInbox(PipelineConfig cfg) throws Exception {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher(cfg.filePattern);
        Path root           = Paths.get(cfg.pollDir).toAbsolutePath();
        Path errorsDir      = Paths.get(cfg.errorsDir).toAbsolutePath();
        Path quarantineDir  = Paths.get(cfg.quarantineDir).toAbsolutePath();
        if (!Files.exists(root)) Files.createDirectories(root);

        MarkerManager.cleanupStaleMarkers(cfg);
        System.out.println("Polling " + root + " with " + cfg.threads + " thread(s)...");

        ExecutorService executor = Executors.newFixedThreadPool(cfg.threads);
        List<Future<?>> futures  = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !p.startsWith(errorsDir))
                .filter(p -> !p.startsWith(quarantineDir))
                .filter(matcher::matches)
                .forEach(p -> futures.add(executor.submit(() -> processFile(p.toFile(), cfg))));
        }

        for (Future<?> f : futures) {
            try   { f.get(); }
            catch (Exception e) { e.printStackTrace(); }
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.HOURS);
    }

    // ── per-file worker ───────────────────────────────────────────────────────

    /**
     * Full processing lifecycle for one input file.
     * Errors are caught, logged, and written to the status CSV without crashing
     * other workers.
     */
    private static void processFile(File inputFile, PipelineConfig cfg) {
        LocalDateTime startTime     = LocalDateTime.now();
        String        status        = "SUCCESS";
        String        errorMsg      = "";
        IngestResult  ingestResult  = new IngestResult(0, 0, 0);
        TransformResult xformResult = TransformResult.empty();

        try {
            if (MarkerManager.isAlreadyProcessed(inputFile, cfg)) {
                System.out.println("Skipping already processed: " + inputFile.getName());
                return;
            }

            System.out.printf("[%s] Processing: %s (%.1f MB)%n",
                    ts(), inputFile.getName(), inputFile.length() / 1_048_576.0);

            // ── schema selection ──────────────────────────────────────────────
            final Map<String, Object> schema;
            final String              table;
            if (cfg.schemaSelector != null) {
                SchemaSelector.Selection sel = cfg.schemaSelector.select(inputFile);
                schema = sel.schema();
                table  = sel.table();
                System.out.printf("[%s] [%s] Schema: %s → table: %s%n",
                        ts(), inputFile.getName(),
                        ((java.util.Map<?, ?>) schema.get("raw")).get("name"), table);
            } else {
                schema = cfg.singleSchema;
                table  = null;
            }

            // ── per-worker DuckDB (temp-file, avoids global native-lib conflicts) ──
            File tempDb = DuckDbUtil.tempDbFile("duckdb_worker_");
            try (Connection conn = DriverManager.getConnection(DuckDbUtil.jdbcUrl(tempDb))) {

                // ── ingest ────────────────────────────────────────────────────
                System.out.printf("[%s] [%s] Ingest: starting...%n", ts(), inputFile.getName());
                long t0 = System.currentTimeMillis();
                try {
                    ingestResult = CsvIngester.ingest(inputFile, conn, schema, cfg);
                    System.out.printf("[%s] [%s] Ingest: done — %,d rows, %,d errors (%,d ms)%n",
                            ts(), inputFile.getName(), ingestResult.parsedRows(),
                            ingestResult.errorRows(), System.currentTimeMillis() - t0);
                } catch (IOException e) {
                    QuarantineManager.quarantine(inputFile, "unreadable", false, cfg);
                    status   = "QUARANTINED_UNREADABLE";
                    errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return;
                }

                // ── quarantine on zero valid rows ─────────────────────────────
                if (ingestResult.parsedRows() == 0
                        && (ingestResult.errorRows() > 0 || ingestResult.junkCandidateRows() > 0)) {
                    QuarantineManager.quarantine(inputFile, "field_mismatch",
                            ingestResult.errorRows() > 0, cfg);
                    status   = "QUARANTINED_MISMATCH";
                    errorMsg = ingestResult.errorRows() > 0
                            ? String.format("0 valid rows; %d row(s) rejected (field mismatch)",
                                    ingestResult.errorRows())
                            : String.format("0 valid rows; %d content line(s) failed column-count in junk scan",
                                    ingestResult.junkCandidateRows());
                    return;
                }

                // ── transform ─────────────────────────────────────────────────
                System.out.printf("[%s] [%s] Transform: starting — %,d rows, %.1f MB input%n",
                        ts(), inputFile.getName(),
                        ingestResult.parsedRows(), inputFile.length() / 1_048_576.0);
                long t1 = System.currentTimeMillis();
                // TEMP shim until a later task rewrites this class for batching.
                DataTransformer.materialize(conn, schema, cfg);
                xformResult = PartitionWriter.write(conn, "transformed",
                        (table != null && !table.isBlank())
                                ? java.nio.file.Paths.get(cfg.databaseDir, table).toString()
                                : cfg.databaseDir,
                        cfg.outputFormat, cfg.compression,
                        CsvIngester.stripExtensions(inputFile.getName()))
                        .stream()
                        .collect(java.util.stream.Collectors.teeing(
                                java.util.stream.Collectors.mapping(PartitionOutput::outputFile, java.util.stream.Collectors.toList()),
                                java.util.stream.Collectors.mapping(PartitionOutput::bytes, java.util.stream.Collectors.toList()),
                                TransformResult::new));
                long totalBytes = xformResult.outputSizes().stream().mapToLong(Long::longValue).sum();
                System.out.printf("[%s] [%s] Transform: done — %d file(s), %.1f MB (%,d ms)%n",
                        ts(), inputFile.getName(), xformResult.outputPaths().size(),
                        totalBytes / 1_048_576.0, System.currentTimeMillis() - t1);

            } finally {
                DuckDbUtil.deleteTempDb(tempDb);
            }

            DuckLakeRegistrar.register(xformResult.outputPaths(), table, cfg);
            MarkerManager.createMarkerFile(inputFile, cfg);
            backupFile(inputFile, cfg);

        } catch (Exception e) {
            status   = "FAILED";
            errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            e.printStackTrace();
        } finally {
            LocalDateTime endTime  = LocalDateTime.now();
            long durationMs = Duration.between(startTime, endTime).toMillis();
            System.out.printf("[%s] Done: %s | status=%s | parsed=%d | errors=%d | duration=%dms%n",
                    endTime.format(DuckDbUtil.DT_FMT), inputFile.getName(), status,
                    ingestResult.parsedRows(), ingestResult.errorRows(), durationMs);
            StatusWriter.append(inputFile.getName(), status, ingestResult, xformResult,
                    startTime, endTime, durationMs, errorMsg, cfg);
        }
    }

    // ── file backup ───────────────────────────────────────────────────────────

    /**
     * Move the successfully processed source file into the backup directory,
     * preserving its path relative to the poll root.
     * No-ops when {@code dirs.backup} is absent or blank.
     */
    private static void backupFile(File inputFile, PipelineConfig cfg) throws IOException {
        if (cfg.backupDir == null || cfg.backupDir.isBlank()) return;
        Path pollPath = Paths.get(cfg.pollDir).toAbsolutePath().normalize();
        Path filePath = inputFile.toPath().toAbsolutePath().normalize();
        Path rel      = pollPath.relativize(filePath);
        Path dst      = Paths.get(cfg.backupDir).resolve(rel);
        Files.createDirectories(dst.getParent());
        Files.move(filePath, dst, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("[%s] [%s] Backup: moved → %s%n", ts(), inputFile.getName(), dst);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String ts() {
        return LocalDateTime.now().format(DuckDbUtil.DT_FMT);
    }
}
