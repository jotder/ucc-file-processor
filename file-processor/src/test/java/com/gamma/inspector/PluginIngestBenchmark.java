package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end throughput benchmark for the unified streaming plugin engine
 * ({@link StreamingPluginBatchStrategy} + {@link DuckDbRecordSink}), measuring both execution modes:
 *
 * <ul>
 *   <li><b>union</b> — the default config-driven mode for files under {@code large_file_bytes}
 *       (one transform/write for the batch).</li>
 *   <li><b>generation</b> — bounded multi-generation flushing (forced via the {@code flushRows}
 *       test seam), the path huge single files take.</li>
 * </ul>
 *
 * <p>The stub decodes the generated {@code ID,EVT_DATE} text file into one segment and runs the full
 * ingest → transform → partitioned-write → lineage path. A background sampler records peak heap so the
 * generation path's bounded-memory property is visible. Both passes must conserve the row count.
 *
 * <p>Gated on {@code -Dbench.run=true} like {@link PipelineBenchmark}. Run:
 * <pre>
 *   mvn -pl file-processor -Dtest=PluginIngestBenchmark -DfailIfNoTests=false \
 *       -Dbench.run=true -Dbench.rows=2000000 -Dbench.days=30 -Dbench.format=PARQUET test
 * </pre>
 */
class PluginIngestBenchmark {

    // ── stub ingester (referenced by FQN from the pipeline toon) ─────────────────

    /** Streaming ingester: emits one record per line; framework owns buffering/flush/write. */
    public static class BenchStreamingIngester implements StreamingFileIngester {
        @Override
        public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
            try (var lines = Files.lines(file.toPath())) {
                for (var it = lines.iterator(); it.hasNext(); ) {
                    String line = it.next();
                    if (line.isBlank()) continue;
                    int comma = line.indexOf(',');
                    sink.emit("EVT", line.substring(0, comma), line.substring(comma + 1));
                }
            }
        }
    }

    // ── benchmark ────────────────────────────────────────────────────────────────

    @Test
    void benchmarkPluginPaths(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"),
                "PluginIngestBenchmark skipped — pass -Dbench.run=true to run it");

        int    rows   = Integer.getInteger("bench.rows", 1_000_000);
        int    days   = Integer.getInteger("bench.days", 30);
        String format = System.getProperty("bench.format", "PARQUET");

        System.out.printf("%n=== PluginIngestBenchmark: %,d rows, %d days, %s ===%n", rows, days, format);

        File input = dir.resolve("events_20200403.bin").toFile();
        long t = System.nanoTime();
        generate(input, rows, days);
        System.out.printf("gen:                    %7.2fs  file=%.1f MB%n",
                secs(t), input.length() / 1_048_576.0);

        PipelineConfig unionCfg = buildConfig(dir, "union", format, BenchStreamingIngester.class.getName());
        PipelineConfig genCfg   = buildConfig(dir, "gen",   format, BenchStreamingIngester.class.getName());

        // 1) union mode (config-driven): the small-files path — one transform/write for the batch
        Result union = run("streaming union (1 batch)   ", unionCfg, rows,
                () -> new StreamingPluginBatchStrategy().ingest(buildBatch(unionCfg, input), unionCfg));

        // 2) generation mode (forced flushRows = rows/4): the huge-file path — bounded scratch
        long budget = Math.max(1, rows / 4);
        Result gen = run("streaming gen (" + ((rows + budget - 1) / budget) + " generations)  ",
                genCfg, rows,
                () -> new StreamingPluginBatchStrategy(budget).ingest(buildBatch(genCfg, input), genCfg));

        System.out.printf("%n--- summary (rows=%,d) ---%n", rows);
        System.out.printf("%-30s %9s %12s %10s %9s%n", "option", "wall", "rows/s", "outFiles", "peakMB");
        for (Result r : List.of(union, gen))
            System.out.printf("%-30s %8.2fs %,12.0f %10d %9d%n",
                    r.label, r.sec, rows / r.sec, r.outFiles, r.peakHeapMB);

        // Correctness: both modes conserve the input row count.
        assertEquals(rows, union.lineageRows, "union mode must conserve rows");
        assertEquals(rows, gen.lineageRows, "generation mode must conserve rows");
    }

    // ── harness ────────────────────────────────────────────────────────────────

    private record Result(String label, double sec, long outFiles, long lineageRows, long peakHeapMB) { }

    private interface IngestCall { IngestOutcome run() throws Exception; }

    private static Result run(String label, PipelineConfig cfg, int rows, IngestCall call) throws Exception {
        cleanDb(cfg);
        System.gc();
        Thread.sleep(50);
        long base = usedHeap();
        HeapSampler sampler = new HeapSampler(base);
        sampler.start();

        long t = System.nanoTime();
        IngestOutcome out = call.run();
        double sec = secs(t);
        sampler.stop();
        sampler.join();

        if (!"SUCCESS".equals(out.status()))
            throw new IllegalStateException(label + " ended " + out.status() + ": " + out.error());
        long lineageRows = out.lineage().stream().mapToLong(LineageRow::rowCount).sum();
        long peakMB = Math.max(0, (sampler.peak - base)) / 1_048_576;
        System.out.printf("%s  %7.2fs  (%,.0f rows/s)  outFiles=%d  lineageRows=%,d  peakHeapΔ=%dMB%n",
                label, sec, rows / sec, out.outputs().size(), lineageRows, peakMB);
        return new Result(label.trim(), sec, out.outputs().size(), lineageRows, peakMB);
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Polls used heap on a tight-ish loop to capture the run's peak. */
    private static final class HeapSampler extends Thread {
        volatile boolean running = true;
        long peak;
        HeapSampler(long base) { this.peak = base; setDaemon(true); }
        void stop()  { running = false; }
        @Override public void run() {
            while (running) {
                long u = usedHeap();
                if (u > peak) peak = u;
                try { Thread.sleep(5); } catch (InterruptedException e) { return; }
            }
        }
    }

    private static double secs(long startNanos) { return (System.nanoTime() - startNanos) / 1e9; }

    private static void cleanDb(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (Files.exists(db)) {
            try (var walk = Files.walk(db)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
            }
        }
    }

    /** Single segment: ID, EVT_DATE → year/month/day partitions. */
    private static void generate(File f, int rows, int days) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(f.toPath())) {
            StringBuilder sb = new StringBuilder(64);
            for (int i = 0; i < rows; i++) {
                int day = (i % days) + 1;
                sb.setLength(0);
                sb.append("E").append(i).append(",2020-04-").append(day < 10 ? "0" : "").append(day).append('\n');
                w.write(sb.toString());
            }
        }
    }

    private static Batch buildBatch(PipelineConfig cfg, File file) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(java.util.Map.of(), null);
        Batch.Member m = new Batch.Member(file, 0, file.length(), sel);
        return new Batch(cfg.identity().runTimestamp() + "_evt_0001", "evt", null, List.of(m));
    }

    private static PipelineConfig buildConfig(Path dir, String tag, String format, String ingesterClass) throws Exception {
        Path schema = dir.resolve("evt_schema_" + tag + ".toon");
        Files.writeString(schema, """
                partitions[3]{column,source,type}:
                  year,EVT_DATE,DATE_YEAR
                  month,EVT_DATE,DATE_MONTH
                  day,EVT_DATE,DATE_DAY
                raw:
                  name: evt
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVT_DATE,"1",DATE
                mapping:
                  canonicalName: evt
                  rawName: evt
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVT_DATE,EVT_DATE,DIRECT
                """);
        String comp = "PARQUET".equals(format) ? "  compression: snappy\n" : "";
        Path pipeline = dir.resolve("evt_pipeline_" + tag + ".toon");
        String base = dir.resolve(tag).toString().replace("\\", "/");
        Files.writeString(pipeline, """
                name: EVT_ETL
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: %s
                %sprocessing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: %s
                  segments:
                    EVT: %s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(base, base, base, base, base, base, base, base,
                format, comp, ingesterClass, schema.toString().replace("\\", "/")));
        return PipelineConfig.load(pipeline.toString());
    }
}
