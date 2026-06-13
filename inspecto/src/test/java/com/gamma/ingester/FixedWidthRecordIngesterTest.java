package com.gamma.ingester;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RecordSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the binary fixed-length {@link FixedWidthRecordIngester}. */
class FixedWidthRecordIngesterTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: rec
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"0",VARCHAR
                EVENT_DATE,"1",DATE
                AMOUNT,"2",DOUBLE
            mapping:
              canonicalName: rec
              rawName: rec
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    @Test
    void slicesFixedLengthRecordsAndRejectsTrailingPartial(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());

        // Three 24-byte records + a 3-byte trailing partial (→ reject).
        byte[] data = ("A000012020-04-03  1234.5"
                     + "B000022020-04-04  9999.0"
                     + "C000032020-04-03    10.0"
                     + "XYZ").getBytes(StandardCharsets.US_ASCII);
        File dat = dir.resolve("rec.bin").toFile();
        Files.write(dat.toPath(), data);

        CapturingSink sink = new CapturingSink();
        new FixedWidthRecordIngester().ingest(dat, sink, 0, cfg);

        assertEquals(List.of("ACCOUNT_NUMBER", "EVENT_DATE", "AMOUNT"), sink.defined, "columns from raw.fields");
        assertEquals(3, sink.emitted.size(), "three full records emitted");
        assertEquals(1, sink.rejects, "the 3-byte trailing partial is rejected");
        assertArrayEquals(new Object[]{"A00001", "2020-04-03", "1234.5"}, sink.emitted.get(0),
                "byte slices decoded + trimmed");
        assertArrayEquals(new Object[]{"B00002", "2020-04-04", "9999.0"}, sink.emitted.get(1));
    }

    @Test
    void missingRecordLengthFails(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, /*recordLength*/ 0).toString());
        File dat = dir.resolve("x.bin").toFile();
        Files.write(dat.toPath(), "A00001".getBytes(StandardCharsets.US_ASCII));
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> new FixedWidthRecordIngester().ingest(dat, new CapturingSink(), 0, cfg));
        assertTrue(e.getMessage().contains("record_length must be > 0"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Path writePipeline(Path dir) throws Exception { return writePipeline(dir, 24); }

    private static Path writePipeline(Path dir, int recordLength) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("rec_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("rec_pipeline.toon");
        Files.writeString(pipe, ("""
                name: FW_BIN
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  status_dir: %s/status
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.bin"
                  ingester: com.gamma.ingester.FixedWidthRecordIngester
                  segments:
                    REC: %s
                  ingester_config:
                    record_length: %d
                    encoding: utf-8
                    trim: both
                    fields[3]{name,start,length}:
                      ACCOUNT_NUMBER,0,6
                      EVENT_DATE,6,10
                      AMOUNT,16,8
                """).formatted(d, d, d, d, d, d, d,
                schema.toString().replace('\\', '/'), recordLength), StandardCharsets.UTF_8);
        return pipe;
    }

    /** A {@link RecordSink} that records every call for assertions. */
    private static final class CapturingSink implements RecordSink {
        List<String> defined;
        final List<Object[]> emitted = new ArrayList<>();
        int rejects, junks;
        @Override public void define(String segmentKey, List<String> columns) { this.defined = columns; }
        @Override public void emit(String segmentKey, Object... values) { emitted.add(values); }
        @Override public void reject(String segmentKey) { rejects++; }
        @Override public void junk() { junks++; }
    }
}
