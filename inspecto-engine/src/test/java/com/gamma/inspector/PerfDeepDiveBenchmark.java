package com.gamma.inspector;

import com.gamma.etl.MarkerManager;
import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ForkJoinPool;

/**
 * Deep-dive performance harness for the v3.12.0 pipeline-perf pass — the parts the
 * stage-isolating {@code PipelineBenchmark}/{@code PluginIngestBenchmark} don't reach
 * because they run single-file / single-batch / threads=1.
 *
 * <p>All three tests are gated on {@code -Dbench.run=true}. Each prints measured numbers.
 *
 * <ul>
 *   <li>{@link #concurrencyAndAutoDerive} — #1 auto-derive duckdb_threads. End-to-end
 *       {@code CollectorProcessor.run} over several concurrent multi-member batches; compares
 *       single-batch, auto-divided, and deliberately-oversubscribed thread configs.</li>
 *   <li>{@link #parallelInboxScan} — #6. Times a poll cycle whose inbox is entirely
 *       already-processed (pure scan: walk + per-file marker stat), threads=1 vs threads=N.</li>
 *   <li>{@link #parallelPartitionReveal} — #5. Times {@code PartitionWriter.write} over a
 *       high-cardinality (many-partition) table. A/B the two modes across runs via
 *       {@code -Djava.util.concurrent.ForkJoinPool.common.parallelism=1} (sequential) vs default.</li>
 * </ul>
 */
class PerfDeepDiveBenchmark {

    private static double secs(long startNanos) { return (System.nanoTime() - startNanos) / 1e9; }

    // ── #1 — concurrency + auto-derive duckdb_threads ────────────────────────────

    @Test
    void concurrencyAndAutoDerive(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"), "pass -Dbench.run=true");

        int files       = Integer.getInteger("bench.files", 16);
        int rowsPerFile  = Integer.getInteger("bench.rows", 150_000);
        int days         = Integer.getInteger("bench.days", 30);
        int maxFiles     = Integer.getInteger("bench.maxfiles", 4);
        int cores        = Runtime.getRuntime().availableProcessors();

        System.out.printf("%n=== #1 concurrency / auto-derive duckdb_threads ===%n");
        System.out.printf("cores=%d  files=%d × %,d rows  batch.max_files=%d → %d batches%n",
                cores, files, rowsPerFile, maxFiles, (files + maxFiles - 1) / maxFiles);
        System.out.printf("%-46s %9s%n", "config", "wall");

        // (threads, duckdb_threads, label)
        int[][] variants = {
                {1,  0},   // single batch at a time, all cores — no oversubscription possible
                {4,  0},   // auto: cores ÷ threads per batch (the v3.12.0 default)
                {4, -1},   // opt-out: every batch grabs all cores → threads × cores workers
        };
        String[] labels = {
                "threads=1, duckdb=0 (auto→all cores, 1 batch)",
                "threads=4, duckdb=0 (auto→" + Math.max(1, cores / 4) + "/batch)",
                "threads=4, duckdb=-1 (opt-out→" + cores + "/batch = " + (4 * cores) + " on " + cores + ")",
        };

        for (int v = 0; v < variants.length; v++) {
            Path dir = root.resolve("c" + v);
            PipelineConfig cfg = buildConfig(dir, variants[v][0], variants[v][1], maxFiles);
            Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
            writeCsvFiles(inbox, files, rowsPerFile, days);

            long t = System.nanoTime();
            try {
                CollectorProcessor.run(cfg);
            } catch (CollectorProcessor.BatchProcessingException e) {
                System.out.printf("  (warning: %s)%n", e.getMessage());
            }
            System.out.printf("%-46s %8.2fs%n", labels[v], secs(t));
        }
    }

    // ── #6 — parallel inbox duplicate-check scan ─────────────────────────────────

    @Test
    void parallelInboxScan(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"), "pass -Dbench.run=true");

        int k = Integer.getInteger("bench.scanfiles", 6000);
        System.out.printf("%n=== #6 parallel inbox scan (%,d already-processed files) ===%n", k);
        System.out.printf("%-34s %9s%n", "config", "scan wall");

        for (int threads : new int[]{1, 8}) {
            Path dir = root.resolve("s" + threads);
            PipelineConfig cfg = buildConfig(dir, threads, 0, 1000);
            Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
            // Generate k tiny files and pre-mark them all as processed — so the poll cycle is
            // pure scan (walk + k marker stats) with zero batch processing.
            for (int i = 0; i < k; i++) {
                File f = inbox.resolve("f" + i + ".csv").toFile();
                Files.writeString(f.toPath(), "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");
                MarkerManager.createMarkerFile(f, cfg);
            }
            long t = System.nanoTime();
            CollectorProcessor.run(cfg);   // finds all k already-processed → no candidates
            System.out.printf("threads=%-26d %8.3fs%n", threads, secs(t));
        }
    }

    // ── #5 — parallel partition reveal at high cardinality ───────────────────────

    @Test
    void parallelPartitionReveal(@TempDir Path root) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"), "pass -Dbench.run=true");

        int parts = Integer.getInteger("bench.parts", 3000);
        int cores = Runtime.getRuntime().availableProcessors();
        System.out.printf("%n=== #5 partition reveal (%d partitions) ===%n", parts);
        // A parallel stream's terminal op runs on the ForkJoinPool that submitted it, so wrapping
        // PartitionWriter.write in a pool of size 1 forces the reveal sequential and size=cores
        // forces it parallel — a clean in-JVM A/B of the reveal fan-out. Both calls run the same
        // COPY from `transformed`, so the wall-clock delta isolates the reveal.
        File db = DuckDbUtil.tempDbFile("reveal_");
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            // One row per distinct day → `parts` distinct (year,month,day) partitions.
            st.execute("CREATE TABLE transformed AS SELECT " +
                    "  'id_' || d AS ID, " +
                    "  CAST(EXTRACT(year  FROM dt) AS VARCHAR) AS year, " +
                    "  lpad(CAST(EXTRACT(month FROM dt) AS VARCHAR), 2, '0') AS month, " +
                    "  lpad(CAST(EXTRACT(day   FROM dt) AS VARCHAR), 2, '0') AS day, " +
                    "  0 AS __src_id " +
                    "FROM (SELECT d, DATE '2010-01-01' + CAST(d AS INTEGER) AS dt FROM range(0, " + parts + ") t(d))");

            System.out.printf("%-34s %9s%n", "reveal mode", "wall");
            for (int poolSize : new int[]{1, cores, 1, cores}) {   // interleaved ×2 to average out warmup
                String outDir = root.resolve("out_" + poolSize + "_" + System.nanoTime()).toString().replace("\\", "/");
                ForkJoinPool pool = new ForkJoinPool(poolSize);
                long t = System.nanoTime();
                List<PartitionOutput> outs = pool.submit(() -> {
                    try {
                        return PartitionWriter.write(conn, "transformed", outDir, "PARQUET", "snappy",
                                "bench", List.of("year", "month", "day"));
                    } catch (Exception e) { throw new RuntimeException(e); }
                }).get();
                pool.shutdown();
                double sec = secs(t);
                System.out.printf("pool=%-2d (%s)  write+reveal:%7.3fs  files=%d%n",
                        poolSize, poolSize == 1 ? "sequential" : "parallel  ", sec, outs.size());
            }
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void writeCsvFiles(Path inbox, int files, int rowsPerFile, int days) throws Exception {
        for (int n = 0; n < files; n++) {
            try (BufferedWriter w = Files.newBufferedWriter(inbox.resolve("f" + n + ".csv"))) {
                w.write("ID,AMT,EVENT_DATE\n");
                StringBuilder sb = new StringBuilder(48);
                for (int i = 0; i < rowsPerFile; i++) {
                    int day = (i % days) + 1;
                    sb.setLength(0);
                    sb.append('r').append(n).append('_').append(i).append(',')
                      .append((i % 1000) + 0.5).append(",2020-04-")
                      .append(day < 10 ? "0" : "").append(day).append('\n');
                    w.write(sb.toString());
                }
            }
        }
    }

    private static PipelineConfig buildConfig(Path dir, int threads, int duckdbThreads, int maxFiles)
            throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.createDirectories(dir);
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: perf
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    AMT,"1",DOUBLE
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: perf
                  rawName: perf
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    AMT,AMT,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, """
                name: PERF_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  markers: %s/markers
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: PARQUET
                  compression: snappy
                processing:
                  threads: %d
                  duckdb_threads: %d
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                  schema_file: "%s"
                  batch:
                    max_files: %d
                    max_bytes: 1073741824
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                        threads, duckdbThreads, schema.toString().replace("\\", "/"), maxFiles));
        return PipelineConfig.load(pipeline.toString());
    }
}
