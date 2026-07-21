package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof that the {@code frontend: fixedwidth} text path flows through the unchanged
 * backend: a fixed-width {@code .dat} is carved by {@code read_csv}+{@code substring} (the streaming
 * single-member {@code raw_input} view path), typed by {@code DataTransformer}, and written by
 * {@code PartitionWriter} into the same Hive-partitioned output a CSV source would produce.
 */
class FixedWidthPipelineTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: sub
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"0",VARCHAR
                EVENT_DATE,"1",DATE
                AMOUNT,"2",DOUBLE
            mapping:
              canonicalName: sub
              rawName: sub
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    @Test
    void fixedWidthRecordsPartitionByDate(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir).toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path dat = inbox.resolve("sub.dat");
        // 6 + 10 + 8 = 24 chars/record, two distinct dates → two day partitions.
        Files.writeString(dat, """
                A000012020-04-03  1234.5
                B000022020-04-04  9999.0
                C000032020-04-03    10.0
                """, StandardCharsets.UTF_8);

        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        Batch batch = new Batch(cfg.identity().runTimestamp() + "_fw_0001", "fw", null,
                List.of(new Batch.Member(dat.toFile(), 0, dat.toFile().length(), sel)));

        BatchProcessor.process(batch, cfg, new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));

        // Two day-partitions written (2020-04-03 and 2020-04-04).
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            List<Path> out = w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).toList();
            assertEquals(2, out.size(), "two distinct dates → two partition files, got " + out);
        }
        assertTrue(Files.exists(Path.of(cfg.dirs().database(), "year=2020", "month=04", "day=03")),
                "2020-04-03 partition exists");
        assertTrue(Files.exists(Path.of(cfg.dirs().database(), "year=2020", "month=04", "day=04")),
                "2020-04-04 partition exists");

        // The 04/03 partition holds the two records for that date, with trimmed/typed values.
        String apr03 = readPartition(cfg, "year=2020", "month=04", "day=03");
        assertTrue(apr03.contains("A00001"), "A00001 in 04/03: " + apr03);
        assertTrue(apr03.contains("C00003"), "C00003 in 04/03: " + apr03);
        assertTrue(apr03.contains("1234.5"), "trimmed AMOUNT typed as DOUBLE: " + apr03);
        assertFalse(apr03.contains("B00002"), "B00002 belongs to 04/04, not 04/03");

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
        Path schema = dir.resolve("sub_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        Path grammar = dir.resolve("sub.grammar.toon");
        Files.writeString(grammar, """
                frontend: fixedwidth
                has_header: false
                date_formats[1]: "%Y-%m-%d"
                timestamp_formats[1]: "%Y-%m-%d"
                fixedwidth:
                  record: line
                  trim: both
                  fields[3]{name,start,length}:
                    ACCOUNT_NUMBER,0,6
                    EVENT_DATE,6,10
                    AMOUNT,16,8
                """, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("sub_pipeline.toon");
        Files.writeString(pipe, ("""
                name: FW_PIPE
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
                  file_pattern: "glob:**/*.dat"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                    retention_days: 90
                  grammar: %s
                  schema_file: %s
                """).formatted(d, d, d, d, d, d, d, d,
                grammar.toString().replace('\\', '/'),
                schema.toString().replace('\\', '/')), StandardCharsets.UTF_8);
        return pipe;
    }
}
