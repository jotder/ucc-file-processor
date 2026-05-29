package com.gamma.etl;

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
import java.util.Map;

/**
 * Stage-isolating throughput benchmark for the CSV ETL path.
 *
 * <p>Not part of the normal suite ({@link Disabled}). Run explicitly:
 * <pre>
 *   mvn -Dtest=PipelineBenchmark -DfailIfNoTests=false \
 *       -Dbench.rows=2000000 -Dbench.format=PARQUET test
 * </pre>
 *
 * <p>Times each stage of {@code BatchProcessor}'s CSV path separately —
 * ingest (univocity parse + DuckDB appender), tag (union/__src_id), transform
 * (CREATE TABLE AS SELECT with type casts + partition columns), write
 * (COPY ... PARTITION_BY + file reveal), and lineage (GROUP BY) — so the
 * dominant cost is visible rather than buried in a single wall-clock number.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code bench.rows}   — input row count (default 1,000,000)</li>
 *   <li>{@code bench.days}   — distinct dates → partition fan-out (default 30)</li>
 *   <li>{@code bench.format} — {@code PARQUET} or {@code CSV} (default PARQUET)</li>
 *   <li>{@code bench.cols}   — total raw columns (default 12)</li>
 * </ul>
 *
 * <p>Gated on {@code -Dbench.run=true} so the normal suite skips it (the
 * assumption fails fast and JUnit reports it as skipped, not failed).
 */
class PipelineBenchmark {

    @Test
    void benchmarkCsvPath(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"),
                "PipelineBenchmark skipped — pass -Dbench.run=true to run it");

        int    rows   = Integer.getInteger("bench.rows", 1_000_000);
        int    days   = Integer.getInteger("bench.days", 30);
        int    cols   = Integer.getInteger("bench.cols", 12);
        String format = System.getProperty("bench.format", "PARQUET");
        String comp   = "PARQUET".equals(format) ? "snappy" : null;

        System.out.printf("%n=== PipelineBenchmark: %,d rows, %d cols, %d days, %s ===%n",
                rows, cols, days, format);

        // ── generate input CSV ────────────────────────────────────────────────
        File csv = dir.resolve("bench_input.csv").toFile();
        long genStart = System.nanoTime();
        generateCsv(csv, rows, cols, days);
        double genSec = secs(genStart);
        System.out.printf("gen:       %6.2fs  (%,.0f rows/s)  file=%.1f MB%n",
                genSec, rows / genSec, csv.length() / 1_048_576.0);

        PipelineConfig cfg = buildConfig(dir, format, comp);
        Map<String, Object> schema = buildSchema(cols);
        List<PartitionDef> partDefs = PartitionDef.fromSchema(schema);
        List<String> partCols = PartitionDef.columnNames(partDefs);
        String dbDir = dir.resolve("db").toString().replace("\\", "/");

        File db = DuckDbUtil.tempDbFile("bench_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            // PRAGMA so we measure single-threaded DuckDB unless overridden — makes
            // stage comparison stable. Comment out to see full-parallel numbers.
            // try (Statement s = conn.createStatement()) { s.execute("PRAGMA threads=4"); }

            // ── stage 1: ingest ────────────────────────────────────────────────
            long t = System.nanoTime();
            IngestResult ing = CsvIngester.ingest(csv, conn, schema, cfg, "raw_f0");
            double ingSec = secs(t);
            System.out.printf("ingest:    %6.2fs  (%,.0f rows/s)  parsed=%,d%n",
                    ingSec, ing.parsedRows() / ingSec, ing.parsedRows());

            // ── stage 2: tag (union/__src_id) ──────────────────────────────────
            t = System.nanoTime();
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE raw_input AS SELECT *, CAST(0 AS INTEGER) AS __src_id FROM raw_f0");
            }
            System.out.printf("tag:       %6.2fs%n", secs(t));

            // ── stage 3: transform ─────────────────────────────────────────────
            t = System.nanoTime();
            DataTransformer.materialize(conn, schema, cfg, "raw_input", "transformed");
            double trSec = secs(t);
            System.out.printf("transform: %6.2fs  (%,.0f rows/s)%n", trSec, rows / trSec);

            // ── stage 4: write (COPY PARTITION_BY + reveal) ────────────────────
            t = System.nanoTime();
            List<PartitionOutput> outputs = PartitionWriter.write(
                    conn, "transformed", dbDir, format, comp, "bench", partCols);
            double wrSec = secs(t);
            long outBytes = outputs.stream().mapToLong(PartitionOutput::bytes).sum();
            System.out.printf("write:     %6.2fs  (%,.0f rows/s)  files=%d  out=%.1f MB%n",
                    wrSec, rows / wrSec, outputs.size(), outBytes / 1_048_576.0);

            // ── stage 5: lineage ───────────────────────────────────────────────
            t = System.nanoTime();
            List<LineageRow> lineage = LineageCollector.collect(
                    conn, "transformed", "bench_batch", Map.of(0, csv.getName()), outputs, partCols);
            System.out.printf("lineage:   %6.2fs  (rows=%d)%n", secs(t), lineage.size());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static double secs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    /** Write a CSV: ID, AMT, TXN_DATE, then filler VARCHAR columns. Header row included. */
    private static void generateCsv(File f, int rows, int cols, int days) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(f.toPath())) {
            StringBuilder header = new StringBuilder("ID,AMT,TXN_DATE");
            for (int c = 3; c < cols; c++) header.append(",COL").append(c);
            w.write(header.toString());
            w.write('\n');

            StringBuilder sb = new StringBuilder(256);
            for (int i = 0; i < rows; i++) {
                int day = (i % days) + 1;           // 1..days
                sb.setLength(0);
                sb.append(i).append(',')
                  .append((i % 100000) + (i % 7) * 0.5).append(',')
                  .append("2020-04-").append(day < 10 ? "0" : "").append(day);
                for (int c = 3; c < cols; c++) sb.append(",val_").append(c).append('_').append(i & 1023);
                sb.append('\n');
                w.write(sb.toString());
            }
        }
    }

    /** Schema: ID(VARCHAR), AMT(DOUBLE), TXN_DATE(DATE)→partitions, filler VARCHARs. */
    private static Map<String, Object> buildSchema(int cols) {
        var fields = new java.util.ArrayList<Map<String, Object>>();
        fields.add(Map.of("name", "ID",       "selector", "0", "type", "VARCHAR"));
        fields.add(Map.of("name", "AMT",      "selector", "1", "type", "DOUBLE"));
        fields.add(Map.of("name", "TXN_DATE", "selector", "2", "type", "DATE"));
        for (int c = 3; c < cols; c++)
            fields.add(Map.of("name", "COL" + c, "selector", String.valueOf(c), "type", "VARCHAR"));

        var rules = new java.util.ArrayList<Map<String, Object>>();
        for (Map<String, Object> fld : fields)
            rules.add(Map.of("targetColumn", (String) fld.get("name"),
                    "sourceExpression", (String) fld.get("name"), "transformType", "DIRECT"));

        return Map.of(
                "partitions", List.of(
                        Map.of("column", "year",  "source", "TXN_DATE", "type", "DATE_YEAR"),
                        Map.of("column", "month", "source", "TXN_DATE", "type", "DATE_MONTH"),
                        Map.of("column", "day",   "source", "TXN_DATE", "type", "DATE_DAY")),
                "raw", Map.of("fields", fields),
                "mapping", Map.of("rules", rules));
    }

    private static PipelineConfig buildConfig(Path dir, String format, String comp) throws Exception {
        Path schemaFile = dir.resolve("bench_schema.toon");
        Files.writeString(schemaFile, """
                partitionKey: TXN_DATE
                raw:
                  name: bench
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: bench
                  rawName: bench
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        String compLine = comp != null ? "  compression: " + comp + "\n" : "";
        Path pipeline = dir.resolve("bench_pipeline.toon");
        Files.writeString(pipeline, """
                name: BENCH_ETL
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
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                format, compLine, schemaFile.toString().replace("\\", "/")));
        return PipelineConfig.load(pipeline.toString());
    }
}
