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
 * End-to-end proof that the {@code frontend: text_regex} path flows through the unchanged backend:
 * a line-regular log file is carved by {@code read_csv}(1-col)+{@code regexp_extract} named groups,
 * typed by {@code DataTransformer}, and written by {@code PartitionWriter} into the same
 * Hive-partitioned output a CSV source would produce; non-matching banner/footer lines are dropped.
 */
class TextRegexPipelineTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: logv
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: logv
              rawName: logv
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    @Test
    void logRecordsPartitionByDate(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path log = inbox.resolve("feed.log");
        Files.writeString(log, """
                # billing extract v2 started
                A00001 2020-04-03 1234.5
                B00002 2020-04-04 9999.0
                C00003 2020-04-03 10.0
                === end of feed ===
                """, StandardCharsets.UTF_8);

        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_tr_0001", "tr", null,
                List.of(new Batch.Member(log.toFile(), 0, log.toFile().length(), sel)));

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
        assertFalse(apr03.contains("B00002"), "B00002 belongs to 04/04");
        assertFalse(apr03.contains("billing extract"), "banner line dropped by the pattern filter");

        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertTrue(batches.contains(",SUCCESS,"), "batch committed SUCCESS");
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
        Path schema = dir.resolve("log_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("log_pipeline.toon");
        Files.writeString(pipe, ("""
                name: TR_PIPE
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
                  file_pattern: "glob:**/*.log"
                  schema_file: %s
                  csv_settings:
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                parsing:
                  frontend: text_regex
                  text_regex:
                    pattern: "^(?P<account>[A-Z0-9]+) (?P<event_date>[0-9-]+) (?P<amount>[0-9.]+)$"
                """).formatted(d, d, d, d, d, d, d, d,
                schema.toString().replace('\\', '/')), StandardCharsets.UTF_8);
        return pipe;
    }
}
