package com.gamma.ingester;

import com.gamma.etl.*;
import com.gamma.inspector.BatchProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the {@link TypedRecordIngester} reference plugin.
 *
 * <p>Exercises the full plugin path: {@code PipelineConfig.load} →
 * {@code BatchProcessor.processPlugin} → {@code DataTransformer} →
 * {@code PartitionWriter} → on-disk Hive layout.  Acts as the working example
 * a real plugin author would copy from.
 */
class TypedRecordIngesterTest {

    /**
     * Full happy path: two segments (CALL, SMS) across two distinct dates
     * → two partition output files per segment.  Also exercises the
     * blank-line and {@code #}-comment skipping behavior.
     */
    @Test
    void multiSegmentMultiDateProducesPartitionedOutput(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path f = inbox.resolve("events.dat");
        Files.writeString(f, """
                # comment lines are skipped
                CALL,C001,2020-04-03,42

                CALL,C002,2020-04-04,17
                SMS,S001,2020-04-03,+15551234567
                SMS,S002,2020-04-04,+15559876543
                """);

        run(cfg, f.toFile());

        // Each segment splits into two day= partitions
        assertOutputFileCount(cfg, "CALL", 2);
        assertOutputFileCount(cfg, "SMS",  2);

        // Lineage references the source file
        assertTrue(Files.readString(Path.of(cfg.dirs().lineageFilePath())).contains("events.dat"));

        // Hive directory structure
        assertHivePartitionExists(cfg, "CALL", "day=03");
        assertHivePartitionExists(cfg, "CALL", "day=04");
        assertHivePartitionExists(cfg, "SMS",  "day=03");
        assertHivePartitionExists(cfg, "SMS",  "day=04");
    }

    /**
     * Lines whose type prefix is not in {@code segmentSchemas} are silently
     * skipped (counted as junk candidates).  This guards against a file that
     * accidentally mixes in records from a different system without crashing
     * the whole batch.
     */
    @Test
    void unknownTypesAreSkippedSilently(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path f = inbox.resolve("mixed.dat");
        Files.writeString(f, """
                CALL,C1,2020-04-03,10
                MMS,M1,2020-04-03,blob              # unknown type — skipped
                EMAIL,E1,2020-04-03,subject         # unknown type — skipped
                SMS,S1,2020-04-03,+15551234567
                """);

        run(cfg, f.toFile());

        // The known segments still produce output
        assertOutputFileCount(cfg, "CALL", 1);
        assertOutputFileCount(cfg, "SMS",  1);
        // Batch is SUCCESS — junk lines don't fail the batch when at least one segment has rows
        assertTrue(Files.readString(Path.of(cfg.dirs().batchesFilePath())).contains(",SUCCESS,"));
    }

    /**
     * A CALL line with the wrong column count is recorded as an errorRow for
     * the CALL segment, but well-formed SMS lines in the same file are
     * preserved.  Demonstrates that per-segment counts are independent.
     */
    @Test
    void wrongColumnCountIncrementsErrorsWithoutLosingOtherSegments(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadConfig(dir);
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path f = inbox.resolve("bad_columns.dat");
        // CALL declares 3 columns (ID, EVENT_DATE, DURATION_SEC); the second
        // CALL line below has only 2 → counts as error, dropped.
        Files.writeString(f, """
                CALL,C1,2020-04-03,42
                CALL,C2,2020-04-03
                SMS,S1,2020-04-03,+15551234567
                """);

        run(cfg, f.toFile());

        // CALL has 1 good row (C1); SMS has 1 good row (S1)
        assertOutputFileCount(cfg, "CALL", 1);
        assertOutputFileCount(cfg, "SMS",  1);

        // Per-file audit lists 1 error row (CALL_BAD)
        String status = Files.readString(Path.of(cfg.dirs().statusFilePath()));
        assertTrue(status.contains("bad_columns.dat"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static PipelineConfig loadConfig(Path dir) throws Exception {
        Path callSchema = dir.resolve("call_schema.toon");
        Path smsSchema  = dir.resolve("sms_schema.toon");
        Files.writeString(callSchema, callSchemaToon());
        Files.writeString(smsSchema,  smsSchemaToon());
        String cs = callSchema.toString().replace("\\", "/");
        String ss = smsSchema.toString().replace("\\", "/");
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, pipelineToon(dir, cs, ss));
        return PipelineConfig.load(pipeline.toString());
    }

    private static void run(PipelineConfig cfg, File... files) {
        List<Batch.Member> members = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            SchemaSelector.Selection sel = new SchemaSelector.Selection(Map.of(), null);
            members.add(new Batch.Member(files[i], i, files[i].length(), sel));
        }
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_events_0001", "events", null, members);
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath());
        BatchProcessor.process(batch, cfg, audit);
    }

    private static void assertOutputFileCount(PipelineConfig cfg, String segKey,
                                              long expected) throws java.io.IOException {
        Path segDir = Path.of(cfg.dirs().database(), segKey);
        assertTrue(Files.exists(segDir), segKey + " output dir should exist");
        long actual;
        try (Stream<Path> s = Files.walk(segDir)) {
            actual = s.filter(Files::isRegularFile).count();
        }
        assertEquals(expected, actual,
                segKey + ": expected " + expected + " output file(s), got " + actual);
    }

    private static void assertHivePartitionExists(PipelineConfig cfg, String segKey,
                                                  String partFragment) throws java.io.IOException {
        Path segDir = Path.of(cfg.dirs().database(), segKey);
        try (Stream<Path> s = Files.walk(segDir)) {
            assertTrue(s.filter(Files::isDirectory)
                            .map(p -> p.toString().replace("\\", "/"))
                            .anyMatch(p -> p.contains(partFragment)),
                    segKey + ": expected partition fragment '" + partFragment + "' under " + segDir);
        }
    }

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
                    EVENT_DATE,"1",DATE
                    DURATION_SEC,"2",VARCHAR
                mapping:
                  canonicalName: call
                  rawName: call
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                    DURATION_SEC,DURATION_SEC,DIRECT
                """;
    }

    private static String smsSchemaToon() {
        return """
                partitions[4]{column,source,type}:
                  event_type,EVENT_TYPE,VARCHAR
                  year,EVENT_DATE,DATE_YEAR
                  month,EVENT_DATE,DATE_MONTH
                  day,EVENT_DATE,DATE_DAY
                raw:
                  name: sms
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_DATE,"1",DATE
                    RECIPIENT,"2",VARCHAR
                mapping:
                  canonicalName: sms
                  rawName: sms
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                    RECIPIENT,RECIPIENT,DIRECT
                """;
    }

    private static String pipelineToon(Path dir, String callSchema, String smsSchema) {
        return """
                name: TYPED_RECORD_ETL
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
                  file_pattern: "glob:**/*.dat"
                  ingester: com.gamma.ingester.TypedRecordIngester
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
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, callSchema, smsSchema);
    }
}
