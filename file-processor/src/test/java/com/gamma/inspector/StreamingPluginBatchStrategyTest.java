package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the streaming plugin-ingester path ({@link StreamingPluginBatchStrategy} +
 * {@link DuckDbRecordSink}). A stub {@link StreamingFileIngester} reads a text file and {@code emit}s
 * one record per line into the framework-owned sink — exercising the full transform → partitioned
 * write → lineage path, including bounded multi-generation flushing.
 */
class StreamingPluginBatchStrategyTest {

    // ── stub streaming ingesters ────────────────────────────────────────────────

    /** Emits {@code TYPE,ID,DATE} lines as records; unknown types are counted as junk. */
    public static class StubStreamingIngester implements StreamingFileIngester {
        @Override
        public void ingest(File file, RecordSink sink, int srcId, PipelineConfig cfg) throws Exception {
            for (String line : Files.readAllLines(file.toPath())) {
                if (line.isBlank()) continue;
                String[] p = line.split(",", -1);   // TYPE,ID,DATE
                String type = p[0];
                if (type.equals("CALL") || type.equals("SMS")) {
                    sink.emit(type, p[1], type, p[2]);   // columns: ID, EVENT_TYPE, EVENT_DATE
                } else {
                    sink.junk();
                }
            }
        }
    }

    /** Emits nothing — drives the QUARANTINED_MISMATCH path. */
    public static class EmptyStreamingIngester implements StreamingFileIngester {
        @Override public void ingest(File f, RecordSink s, int srcId, PipelineConfig cfg) { }
    }

    /** Throws on read — drives the QUARANTINED_UNREADABLE path. */
    public static class UnreadableStreamingIngester implements StreamingFileIngester {
        @Override public void ingest(File f, RecordSink s, int srcId, PipelineConfig cfg) throws Exception {
            throw new IOException("cannot decode " + f.getName());
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void streamsAcrossMultipleGenerationsConservingRows(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setup(dir, StubStreamingIngester.class.getName());
        File input = writeInput(cfg, "events_20200403.bin",
                "CALL,C001,2020-04-03\n" +
                "CALL,C002,2020-04-03\n" +
                "CALL,C003,2020-04-04\n" +
                "SMS,S001,2020-04-03\n" +
                "SMS,S002,2020-04-04\n");
        Batch batch = buildBatch(cfg, input);

        // flushRows=2 forces several bounded generations over 5 records.
        IngestOutcome out = new StreamingPluginBatchStrategy(2).ingest(batch, cfg);

        assertEquals("SUCCESS", out.status());
        assertEquals(5, out.totalInputRows(), "all 5 emitted records are accepted");
        long outputRows = out.lineage().stream().mapToLong(LineageRow::rowCount).sum();
        assertEquals(5, outputRows, "row count is conserved across generations");
        assertTrue(out.outputs().size() >= 2,
                "small flush budget must produce multiple generation files, got " + out.outputs().size());

        // Distinct generation stems landed on disk (…_gNNNNN_out.csv).
        long genFiles;
        try (Stream<Path> s = Files.walk(Path.of(cfg.dirs().database()))) {
            genFiles = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().contains("_g"))
                    .count();
        }
        assertTrue(genFiles >= 2, "expected multiple generation output files, found " + genFiles);
    }

    @Test
    void routedThroughBatchProcessorWhenStreaming(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setup(dir, StubStreamingIngester.class.getName());
        File input = writeInput(cfg, "events_20200403.bin",
                "CALL,C001,2020-04-03\nSMS,S001,2020-04-03\n");
        Batch batch = buildBatch(cfg, input);
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath());

        // BatchProcessor must detect StreamingFileIngester and route to the streaming strategy.
        BatchProcessor.process(batch, cfg, audit);

        assertTrue(Files.exists(Path.of(cfg.dirs().database(), "CALL")), "CALL output should exist");
        assertTrue(Files.exists(Path.of(cfg.dirs().database(), "SMS")), "SMS output should exist");
    }

    @Test
    void zeroRecordsQuarantinedAsMismatch(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setup(dir, EmptyStreamingIngester.class.getName());
        File input = writeInput(cfg, "empty_20200403.bin", "garbage that the ingester ignores\n");
        Batch batch = buildBatch(cfg, input);

        IngestOutcome out = new StreamingPluginBatchStrategy().ingest(batch, cfg);

        assertEquals("EMPTY", out.status());
        assertEquals(1, out.memberAudits().size());
        assertEquals("QUARANTINED_MISMATCH", out.memberAudits().get(0).status());
    }

    @Test
    void decodeFailureQuarantinedAsUnreadable(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = setup(dir, UnreadableStreamingIngester.class.getName());
        File input = writeInput(cfg, "bad_20200403.bin", "anything\n");
        Batch batch = buildBatch(cfg, input);

        IngestOutcome out = new StreamingPluginBatchStrategy().ingest(batch, cfg);

        assertEquals("EMPTY", out.status());
        assertEquals("QUARANTINED_UNREADABLE", out.memberAudits().get(0).status());
    }

    // ── harness ─────────────────────────────────────────────────────────────────

    private static PipelineConfig setup(Path dir, String ingesterClass) throws Exception {
        Path callSchema = dir.resolve("call_schema.toon");
        Path smsSchema  = dir.resolve("sms_schema.toon");
        Files.writeString(callSchema, callSchemaToon());
        Files.writeString(smsSchema,  smsSchemaToon());
        Path pipeline = dir.resolve("events_pipeline.toon");
        Files.writeString(pipeline, pipelineToon(dir, callSchema, smsSchema, ingesterClass));
        return PipelineConfig.load(pipeline.toString());
    }

    private static File writeInput(PipelineConfig cfg, String name, String content) throws Exception {
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path f = inbox.resolve(name);
        Files.writeString(f, content);
        return f.toFile();
    }

    private static Batch buildBatch(PipelineConfig cfg, File file) {
        SchemaSelector.Selection sel = new SchemaSelector.Selection(Map.of(), null);
        Batch.Member m = new Batch.Member(file, 0, file.length(), sel);
        return new Batch(cfg.identity().runTimestamp() + "_events_0001", "events", null, List.of(m));
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
        return callSchemaToon().replace("name: call", "name: sms")
                               .replace("rawName: call", "rawName: sms")
                               .replace("canonicalName: call", "canonicalName: sms");
    }

    private static String pipelineToon(Path dir, Path callSchema, Path smsSchema, String ingesterClass) {
        String cs = callSchema.toString().replace("\\", "/");
        String ss = smsSchema.toString().replace("\\", "/");
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
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, ingesterClass, cs, ss);
    }
}
