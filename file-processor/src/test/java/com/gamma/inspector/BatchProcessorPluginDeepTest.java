package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deep-dive integration tests for the plugin-ingester path in {@link BatchProcessor}.
 *
 * <p>Covers: multi-member batches with per-date partition splitting, quarantine via
 * IOException and zero rows, empty-segment omission, mixed-batch partial survival,
 * data row-count accuracy in output CSVs, and lineage file content.
 */
class BatchProcessorPluginDeepTest {

    // ── stub ingesters ────────────────────────────────────────────────────────

    /**
     * Normal ingester: "CALL,id,date" lines → CALL table; "SMS,id,date" → SMS table.
     * EVENT_TYPE is added as a derived partition column alongside the payload.
     */
    public static class StubEventIngester implements FileIngester {
        @Override
        public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
                throws Exception {
            List<String[]> callRows = new ArrayList<>(), smsRows = new ArrayList<>();
            for (String line : Files.readAllLines(file.toPath())) {
                if (line.startsWith("CALL,")) callRows.add(line.split(",", 3));
                else if (line.startsWith("SMS,"))  smsRows.add(line.split(",", 3));
            }
            return List.of(
                    populateTable(conn, "CALL", "raw_CALL_f" + srcId, callRows),
                    populateTable(conn, "SMS",  "raw_SMS_f"  + srcId, smsRows));
        }

        private Segment populateTable(Connection conn, String key, String table,
                                      List<String[]> rows) throws Exception {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE \"" + table
                        + "\" (ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE VARCHAR)");
                for (String[] r : rows)
                    st.execute(String.format(
                            "INSERT INTO \"%s\" VALUES ('%s','%s','%s')",
                            table, r[1], key, r[2]));
            }
            return new Segment(key, table, new IngestResult(rows.size(), 0, 0));
        }
    }

    /** Always throws IOException — simulates an unreadable / corrupt file. */
    public static class ThrowingIngester implements FileIngester {
        @Override
        public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
                throws Exception {
            throw new IOException("Simulated unreadable: " + file.getName());
        }
    }

    /** Creates tables but inserts 0 rows — simulates an event-less file. */
    public static class ZeroRowIngester implements FileIngester {
        @Override
        public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
                throws Exception {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE \"raw_CALL_f" + srcId
                        + "\" (ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE VARCHAR)");
                st.execute("CREATE TABLE \"raw_SMS_f" + srcId
                        + "\" (ID VARCHAR, EVENT_TYPE VARCHAR, EVENT_DATE VARCHAR)");
            }
            return List.of(
                    new Segment("CALL", "raw_CALL_f" + srcId, new IngestResult(0, 0, 0)),
                    new Segment("SMS",  "raw_SMS_f"  + srcId, new IngestResult(0, 0, 0)));
        }
    }

    /**
     * Selective ingester: throws IOException for files whose name starts with {@code "bad_"};
     * all other files are processed normally (like {@link StubEventIngester}).
     */
    public static class SelectiveThrowIngester implements FileIngester {
        private final StubEventIngester delegate = new StubEventIngester();

        @Override
        public List<Segment> ingest(File file, Connection conn, int srcId, PipelineConfig cfg)
                throws Exception {
            if (file.getName().startsWith("bad_"))
                throw new IOException("Selective failure: " + file.getName());
            return delegate.ingest(file, conn, srcId, cfg);
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Two-member batch where each input file contributes CALL+SMS rows on a different date.
     *
     * <p>Expected output:
     * <ul>
     *   <li>CALL/ — 2 files (one per date partition 04-03 and 04-04)</li>
     *   <li>SMS/  — 2 files (one per date partition 04-03 and 04-04)</li>
     * </ul>
     * The lineage CSV must reference both source file names, and batches must show SUCCESS.
     */
    @Test
    void multiMemberBatchSplitsPerDateAndWritesLineage(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, StubEventIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));

        // f1: 2 CALL + 1 SMS on 2020-04-03
        Path f1 = inbox.resolve("events_day1.bin");
        Files.writeString(f1,
                "CALL,C001,2020-04-03\nCALL,C002,2020-04-03\nSMS,S001,2020-04-03\n");
        // f2: 1 CALL + 2 SMS on 2020-04-04
        Path f2 = inbox.resolve("events_day2.bin");
        Files.writeString(f2,
                "CALL,C003,2020-04-04\nSMS,S002,2020-04-04\nSMS,S003,2020-04-04\n");

        run(cfg, f1.toFile(), f2.toFile());

        // One output file per date partition for each segment
        assertOutputFileCount(cfg, "CALL", 2);
        assertOutputFileCount(cfg, "SMS",  2);

        // Lineage CSV must reference both input files
        String lineage = Files.readString(Path.of(cfg.lineageFilePath));
        assertTrue(lineage.contains("events_day1.bin"), "lineage missing events_day1.bin");
        assertTrue(lineage.contains("events_day2.bin"), "lineage missing events_day2.bin");

        // Batch audit: SUCCESS
        assertTrue(Files.readString(Path.of(cfg.batchesFilePath)).contains(",SUCCESS,"));
    }

    /**
     * A member whose ingester throws IOException is quarantined as QUARANTINED_UNREADABLE.
     * When all members fail the batch-level status is EMPTY (no survivors).
     */
    @Test
    void memberThrowingIOExceptionIsQuarantinedUnreadable(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, ThrowingIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));
        Path bad = inbox.resolve("corrupt.bin");
        Files.writeString(bad, "binary garbage");

        run(cfg, bad.toFile());

        assertFileExistsInTree(cfg.quarantineDir, "corrupt.bin");
        assertTrue(Files.readString(Path.of(cfg.statusFilePath))
                .contains("QUARANTINED_UNREADABLE"), "status should be QUARANTINED_UNREADABLE");
        assertTrue(Files.readString(Path.of(cfg.batchesFilePath))
                .contains(",EMPTY,"), "batches.csv should be EMPTY when all members fail");
    }

    /**
     * A member whose ingester returns 0 rows for every segment is quarantined
     * as QUARANTINED_MISMATCH.
     */
    @Test
    void memberWithZeroRowsIsQuarantinedMismatch(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, ZeroRowIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));
        Path empty = inbox.resolve("noevents.bin");
        Files.writeString(empty, "nothing to parse");

        run(cfg, empty.toFile());

        assertFileExistsInTree(cfg.quarantineDir, "noevents.bin");
        assertTrue(Files.readString(Path.of(cfg.statusFilePath))
                .contains("QUARANTINED_MISMATCH"), "status should be QUARANTINED_MISMATCH");
    }

    /**
     * When a file contains only CALL rows (SMS table has 0 rows), the SMS segment is skipped
     * entirely — the SMS output directory must not be created.
     */
    @Test
    void emptySegmentIsOmittedFromOutput(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, StubEventIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));
        Path callOnly = inbox.resolve("callonly.bin");
        Files.writeString(callOnly, "CALL,C001,2020-04-03\nCALL,C002,2020-04-03\n");

        run(cfg, callOnly.toFile());

        assertOutputFileCount(cfg, "CALL", 1);
        assertFalse(Files.exists(Path.of(cfg.databaseDir, "SMS")),
                "SMS output dir must not exist when the ingester produced 0 SMS rows");
    }

    /**
     * Mixed batch: one member succeeds, one member's ingester throws IOException.
     *
     * <p>Expected: output from the surviving member; bad member quarantined;
     * batch-level status SUCCESS (at least one survivor).
     */
    @Test
    void mixedBatchQuarantinesBadMemberProducesOutputForGood(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, SelectiveThrowIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));

        // "bad_" prefix triggers SelectiveThrowIngester
        Path good = inbox.resolve("good_events.bin");
        Path bad  = inbox.resolve("bad_corrupt.bin");
        Files.writeString(good, "CALL,C001,2020-04-03\nSMS,S001,2020-04-03\n");
        Files.writeString(bad,  "will throw");

        run(cfg, good.toFile(), bad.toFile());

        // Survivor produced output
        assertOutputFileCount(cfg, "CALL", 1);
        assertOutputFileCount(cfg, "SMS",  1);

        // Bad member quarantined
        assertFileExistsInTree(cfg.quarantineDir, "bad_corrupt.bin");

        // Batch-level SUCCESS (one survivor present)
        assertTrue(Files.readString(Path.of(cfg.batchesFilePath)).contains(",SUCCESS,"),
                "batches.csv should be SUCCESS with at least one surviving member");

        // Per-file: one SUCCESS row and one QUARANTINED_UNREADABLE row
        String status = Files.readString(Path.of(cfg.statusFilePath));
        assertTrue(status.contains("QUARANTINED_UNREADABLE"), "bad file should be QUARANTINED_UNREADABLE");
        assertTrue(status.contains(",SUCCESS,"),              "good file should be SUCCESS");
    }

    /**
     * Verifies that data rows written to output CSV files match the number of input rows.
     *
     * <p>Input: 3 CALL rows + 2 SMS rows, all on the same date.
     * Each segment's output CSV (one file each) must contain exactly the matching row count.
     */
    @Test
    void outputCsvRowCountsMatchInputRows(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, StubEventIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));
        Path f = inbox.resolve("counted.bin");
        Files.writeString(f,
                "CALL,C1,2020-04-03\nCALL,C2,2020-04-03\nCALL,C3,2020-04-03\n"
                + "SMS,S1,2020-04-03\nSMS,S2,2020-04-03\n");

        run(cfg, f.toFile());

        // Count data rows (DuckDB CSV output has a header row)
        assertEquals(3, countDataRowsInOutput(cfg, "CALL"), "CALL output should have 3 data rows");
        assertEquals(2, countDataRowsInOutput(cfg, "SMS"),  "SMS output should have 2 data rows");

        // Lineage CSV should reference the source file
        assertTrue(Files.readString(Path.of(cfg.lineageFilePath)).contains("counted.bin"),
                "lineage.csv should reference counted.bin");
    }

    /**
     * Single member with CALL+SMS rows spanning two distinct dates produces correctly
     * named Hive-partition directories containing the right data.
     */
    @Test
    void partitionDirectoriesReflectEventDates(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir, StubEventIngester.class.getName());
        Path inbox = Files.createDirectories(Path.of(cfg.pollDir));
        Path f = inbox.resolve("mixed_dates.bin");
        Files.writeString(f,
                "CALL,C1,2020-04-03\n"
                + "CALL,C2,2020-04-04\n"
                + "SMS,S1,2020-04-03\n");

        run(cfg, f.toFile());

        // CALL: two date partitions
        Path callDir = Path.of(cfg.databaseDir, "CALL");
        assertTrue(Files.exists(callDir));
        try (Stream<Path> s = Files.walk(callDir)) {
            List<String> partPaths = s
                    .filter(Files::isDirectory)
                    .map(p -> p.toString().replace("\\", "/"))
                    .filter(p -> p.contains("day="))
                    .toList();
            assertTrue(partPaths.stream().anyMatch(p -> p.contains("day=03")),
                    "Expected CALL partition for day=03");
            assertTrue(partPaths.stream().anyMatch(p -> p.contains("day=04")),
                    "Expected CALL partition for day=04");
        }

        // SMS: one date partition (only 2020-04-03)
        assertOutputFileCount(cfg, "SMS", 1);
        try (Stream<Path> s = Files.walk(Path.of(cfg.databaseDir, "SMS"))) {
            assertTrue(s.filter(Files::isDirectory)
                         .map(p -> p.toString().replace("\\", "/"))
                         .anyMatch(p -> p.contains("day=03")),
                    "Expected SMS partition for day=03");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static PipelineConfig loadConfig(Path dir, String ingesterClass) throws Exception {
        Path callSchema = dir.resolve("call_schema.toon");
        Path smsSchema  = dir.resolve("sms_schema.toon");
        Files.writeString(callSchema, callSchemaToon());
        Files.writeString(smsSchema,  smsSchemaToon());
        String cs = callSchema.toString().replace("\\", "/");
        String ss = smsSchema.toString().replace("\\", "/");
        Path pipeline = dir.resolve("events_pipeline.toon");
        Files.writeString(pipeline, pipelineToon(dir, ingesterClass, cs, ss));
        return PipelineConfig.load(pipeline.toString());
    }

    private static void run(PipelineConfig cfg, File... files) {
        Batch batch = buildBatch(cfg, files);
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.statusFilePath, cfg.batchesFilePath, cfg.lineageFilePath);
        BatchProcessor.process(batch, cfg, audit);
    }

    private static Batch buildBatch(PipelineConfig cfg, File... files) {
        List<Batch.Member> members = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            SchemaSelector.Selection sel = new SchemaSelector.Selection(Map.of(), null);
            members.add(new Batch.Member(files[i], i, files[i].length(), sel));
        }
        return new Batch(cfg.runTimestamp + "_events_0001", "events", null, members);
    }

    /** Asserts that the segment output directory exists and contains exactly {@code expected} files. */
    private static void assertOutputFileCount(PipelineConfig cfg, String segKey,
                                              long expected) throws IOException {
        Path segDir = Path.of(cfg.databaseDir, segKey);
        assertTrue(Files.exists(segDir), segKey + " output directory should exist");
        long actual;
        try (Stream<Path> s = Files.walk(segDir)) {
            actual = s.filter(Files::isRegularFile).count();
        }
        assertEquals(expected, actual,
                segKey + ": expected " + expected + " output file(s), got " + actual);
    }

    /** Walks {@code rootDir} and asserts that {@code filename} appears somewhere in the tree. */
    private static void assertFileExistsInTree(String rootDir, String filename) throws IOException {
        Path root = Path.of(rootDir);
        assertTrue(Files.exists(root), "Root directory should exist: " + rootDir);
        try (Stream<Path> s = Files.walk(root)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().equals(filename)),
                    filename + " not found under " + rootDir);
        }
    }

    /**
     * Finds the single output CSV file for {@code segKey} and counts data rows,
     * excluding the header line that DuckDB writes.
     */
    private static long countDataRowsInOutput(PipelineConfig cfg, String segKey)
            throws IOException {
        Path segDir = Path.of(cfg.databaseDir, segKey);
        Path csvFile;
        try (Stream<Path> s = Files.walk(segDir)) {
            csvFile = s.filter(Files::isRegularFile).findFirst()
                    .orElseThrow(() -> new AssertionError(segKey + " output file not found"));
        }
        try (Stream<String> lines = Files.lines(csvFile)) {
            return lines.skip(1).filter(l -> !l.isBlank()).count();
        }
    }

    // ── schema / pipeline toon builders ──────────────────────────────────────

    private static String callSchemaToon() {
        return """
                partitions[4]{column,source,type}:
                  event_type,EVENT_TYPE,VARCHAR
                  year,EVENT_DATE,DATE_YEAR
                  month,EVENT_DATE,DATE_MONTH
                  day,EVENT_DATE,DATE_DAY
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
        return callSchemaToon()
                .replace("name: call",         "name: sms")
                .replace("rawName: call",       "rawName: sms")
                .replace("canonicalName: call", "canonicalName: sms");
    }

    private static String pipelineToon(Path dir, String ingesterClass,
                                       String callSchema, String smsSchema) {
        return """
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
                ingesterClass, callSchema, smsSchema);
    }
}
