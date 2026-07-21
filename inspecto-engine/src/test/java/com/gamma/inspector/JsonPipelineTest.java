package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that the {@code frontend: json} path flows through the unchanged backend: an
 * NDJSON file is read by {@code read_ndjson} (VARCHAR columns keyed by the schema selectors = JSON
 * keys), typed by {@code DataTransformer}, and written by {@code PartitionWriter} into the same
 * Hive-partitioned output a CSV source would produce — with a malformed line routed away from the
 * output instead of failing the batch.
 */
class JsonPipelineTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    @Test
    void ndjsonRecordsPartitionByDate(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path jsonl = inbox.resolve("ev.jsonl");
        // Two distinct dates → two day partitions; one malformed line → rejected, not fatal.
        Files.writeString(jsonl, """
                {"account":"A00001","event_date":"2020-04-03","amount":1234.5}
                {"account":"B00002","event_date":"2020-04-04","amount":9999.0}
                {malformed json line !!!
                {"account":"C00003","event_date":"2020-04-03","amount":10.0}
                """, StandardCharsets.UTF_8);

        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_js_0001", "js", null,
                List.of(new Batch.Member(jsonl.toFile(), 0, jsonl.toFile().length(), sel)));

        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            List<Path> out = w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList();
            assertEquals(2, out.size(), "two distinct dates → two partition files, got " + out);
        }
        assertTrue(Files.exists(Path.of(cfg.dirs().database(), "year=2020", "month=04", "day=03")));
        assertTrue(Files.exists(Path.of(cfg.dirs().database(), "year=2020", "month=04", "day=04")));

        String apr03 = readPartition(cfg, "year=2020", "month=04", "day=03");
        assertTrue(apr03.contains("A00001"), "A00001 in 04/03: " + apr03);
        assertTrue(apr03.contains("C00003"), "C00003 in 04/03: " + apr03);
        assertTrue(apr03.contains("1234.5"), "AMOUNT typed as DOUBLE: " + apr03);
        assertFalse(apr03.contains("B00002"), "B00002 belongs to 04/04");
        assertFalse(apr03.contains("malformed"), "malformed line routed away from the output");

        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(",SUCCESS,"), "batch committed SUCCESS despite the malformed line");
    }

    private static String readPartition(PipelineConfig cfg, String... parts) throws Exception {
        Path p = Path.of(cfg.dirs().database(), parts);
        try (Stream<Path> w = Files.walk(p)) {
            Path out = w.filter(f -> f.getFileName().toString().endsWith("_out.csv")).findFirst().orElseThrow();
            return Files.readString(out);
        }
    }

    private static Path writePipeline(Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("ev_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("ev_pipeline.toon");
        Files.writeString(pipe, ("""
                name: JSON_PIPE
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
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.jsonl"
                  schema_file: %s
                  csv_settings:
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                parsing:
                  frontend: json
                  json:
                    format: newline
                """).formatted(d, d, d, d, d, d, d, d,
                schema.toString().replace('\\', '/')), StandardCharsets.UTF_8);
        return pipe;
    }
}
