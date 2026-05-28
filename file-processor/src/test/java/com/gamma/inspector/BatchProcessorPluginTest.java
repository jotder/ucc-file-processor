package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the plugin-ingester path in {@link BatchProcessor}.
 *
 * <p>Uses a stub {@link FileIngester} ({@link StubEventIngester}) registered as an
 * inner class so there is no external JAR dependency. The stub reads lines from a
 * plain text file and routes them into two DuckDB tables: {@code raw_CALL_f<srcId>}
 * and {@code raw_SMS_f<srcId>}.
 */
class BatchProcessorPluginTest {

    // ── stub ingester ─────────────────────────────────────────────────────────

    /**
     * Lines starting with "CALL," become CALL rows; "SMS," become SMS rows.
     * Each row gets an EVENT_TYPE column (the type key) and an EVENT_DATE column.
     * Table names: raw_CALL_f{srcId}, raw_SMS_f{srcId}.
     */
    public static class StubEventIngester implements FileIngester {
        @Override
        public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
                throws Exception {
            List<String[]> callRows = new ArrayList<>();
            List<String[]> smsRows  = new ArrayList<>();
            for (String line : Files.readAllLines(file.toPath())) {
                if (line.startsWith("CALL,")) {
                    String[] p = line.split(",", 3);
                    callRows.add(p);
                } else if (line.startsWith("SMS,")) {
                    String[] p = line.split(",", 3);
                    smsRows.add(p);
                }
            }

            List<Segment> segs = new ArrayList<>();
            segs.add(populateTable(conn, "CALL", "raw_CALL_f" + srcId, callRows, srcId));
            segs.add(populateTable(conn, "SMS",  "raw_SMS_f"  + srcId, smsRows,  srcId));
            return segs;
        }

        private Segment populateTable(Connection conn, String key, String table,
                                       List<String[]> rows, int srcId) throws Exception {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE \"" + table
                        + "\" (ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE VARCHAR)");
                for (String[] r : rows) {
                    // r[0]=type, r[1]=id, r[2]=date
                    st.execute(String.format(
                            "INSERT INTO \"%s\" VALUES ('%s','%s','%s')",
                            table, r[1], key, r[2]));
                }
            }
            return new Segment(key, table, new IngestResult(rows.size(), 0, 0));
        }
    }

    // ── test ──────────────────────────────────────────────────────────────────

    @Test
    void pluginIngesterProducesTwoSegmentOutputs(@TempDir Path dir) throws Exception {
        // Write pipeline toon (ingester + segments)
        Path callSchema = dir.resolve("call_schema.toon");
        Path smsSchema  = dir.resolve("sms_schema.toon");
        Files.writeString(callSchema, callSchemaToon());
        Files.writeString(smsSchema,  smsSchemaToon());

        String pipeline = writePipelineToon(dir, callSchema, smsSchema);
        PipelineConfig cfg = PipelineConfig.load(pipeline);

        // Input file with CALL and SMS events
        Path inbox = Path.of(cfg.pollDir);
        Files.createDirectories(inbox);
        Path inputFile = inbox.resolve("events_20200403.bin");
        Files.writeString(inputFile,
                "CALL,C001,2020-04-03\n" +
                "CALL,C002,2020-04-03\n" +
                "SMS,S001,2020-04-03\n");

        Batch batch = buildBatch(cfg, inputFile.toFile());
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath);

        BatchProcessor.process(batch, cfg, audit);

        // CALL output: database/CALL/year=2020/month=04/day=03/...
        Path callOut = Path.of(cfg.databaseDir, "CALL");
        assertTrue(Files.exists(callOut),
                "CALL output directory should exist under " + cfg.databaseDir);
        long callFiles;
        try (Stream<Path> s = Files.walk(callOut)) {
            callFiles = s.filter(Files::isRegularFile).count();
        }
        assertTrue(callFiles > 0, "CALL should have at least one output file");

        // SMS output
        Path smsOut = Path.of(cfg.databaseDir, "SMS");
        assertTrue(Files.exists(smsOut),
                "SMS output directory should exist under " + cfg.databaseDir);
        long smsFiles;
        try (Stream<Path> s = Files.walk(smsOut)) {
            smsFiles = s.filter(Files::isRegularFile).count();
        }
        assertTrue(smsFiles > 0, "SMS should have at least one output file");
    }

    // ── schema toon content ────────────────────────────────────────────────────

    private static String callSchemaToon() {
        return """
                partitions:
                  - column: event_type
                    source: EVENT_TYPE
                    type: VARCHAR
                  - column: year
                    source: EVENT_DATE
                    type: DATE_YEAR
                  - column: month
                    source: EVENT_DATE
                    type: DATE_MONTH
                  - column: day
                    source: EVENT_DATE
                    type: DATE_DAY
                raw:
                  name: call
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_TYPE,"1",VARCHAR
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: call
                  rawName: call
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_TYPE,EVENT_TYPE,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """;
    }

    private static String smsSchemaToon() {
        // Identical structure to call but named sms
        return callSchemaToon().replace("name: call", "name: sms")
                               .replace("rawName: call", "rawName: sms")
                               .replace("canonicalName: call", "canonicalName: sms");
    }

    private static String writePipelineToon(Path dir, Path callSchema, Path smsSchema)
            throws Exception {
        Path pipeline = dir.resolve("events_pipeline.toon");
        String cs = callSchema.toString().replace("\\", "/");
        String ss = smsSchema.toString().replace("\\", "/");
        String ingesterClass = StubEventIngester.class.getName();
        String toon = """
                name: EVENTS_ETL
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
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: %s
                  segments:
                    CALL: %s
                    SMS: %s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir,
                ingesterClass, cs, ss);
        Files.writeString(pipeline, toon);
        return pipeline.toString();
    }

    private static Batch buildBatch(PipelineConfig cfg, File file) {
        // For the plugin path, selection schema is unused by BatchProcessor (segmentSchemas used instead)
        SchemaSelector.Selection sel = new SchemaSelector.Selection(Map.of(), null);
        Batch.Member m = new Batch.Member(file, 0, file.length(), sel);
        return new Batch(cfg.runTimestamp + "_events_0001", "events", null, List.of(m));
    }
}
