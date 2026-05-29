package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigValidator}.
 *
 * <p>{@code ConfigValidator} runs after {@link PipelineConfig#load} and emits
 * non-fatal warnings for suspicious-but-legal patterns.  These tests load
 * configs that intentionally trigger each warning and confirm it's emitted.
 */
class ConfigValidatorTest {

    @Test
    void warnsWhenNoPartitionsDeclared(@TempDir Path dir) throws Exception {
        // Single schema, no partitionKey, no partitions[].
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                raw:
                  name: x
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID,"0",VARCHAR
                mapping:
                  canonicalName: x
                  rawName: x
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                """);
        PipelineConfig cfg = loadPipeline(dir, schema);
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("No partitions[] or partitionKey")),
                "Expected partitions warning. Got: " + warnings);
    }

    @Test
    void warnsWhenDateFormatsEmpty(@TempDir Path dir) throws Exception {
        Path schema = writeMinimalSchema(dir);
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/"))
                .replace("date_formats[1]: \"%Y-%m-%d\"", "")
                .replace("timestamp_formats[1]: \"%Y-%m-%d\"", ""));
        PipelineConfig cfg = PipelineConfig.load(pipeline.toString());
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("date_formats is empty")),
                "Expected date_formats warning. Got: " + warnings);
    }

    @Test
    void cleanConfigEmitsNoWarnings(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_DATE,"1",DATE
                mapping:
                  canonicalName: x
                  rawName: x
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        PipelineConfig cfg = loadPipeline(dir, schema);
        List<String> warnings = ConfigValidator.validate(cfg);
        assertTrue(warnings.isEmpty(),
                "Clean config should not emit warnings. Got: " + warnings);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static PipelineConfig loadPipeline(Path dir, Path schema) throws Exception {
        Path pipeline = dir.resolve("pipeline.toon");
        Files.writeString(pipeline, basePipeline(dir, schema.toString().replace("\\", "/")));
        return PipelineConfig.load(pipeline.toString());
    }

    private static Path writeMinimalSchema(Path dir) throws Exception {
        Path schema = dir.resolve("schema.toon");
        Files.writeString(schema, """
                partitionKey: EVENT_DATE
                raw:
                  name: x
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_DATE,"1",DATE
                mapping:
                  canonicalName: x
                  rawName: x
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        return schema;
    }

    private static String basePipeline(Path dir, String schemaPath) {
        return """
                name: VALIDATOR_ETL
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
                  file_pattern: "glob:**/*.csv"
                  schema_file: %s
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, schemaPath);
    }
}
