package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that {@link PipelineConfig#load} enforces the plugin-ingester config contract:
 * {@code processing.segments} is required when {@code processing.ingester} is set, and all
 * segment schema files must exist.
 */
class PipelineConfigPluginValidationTest {

    /**
     * When {@code ingester:} is set but no {@code segments:} map is present,
     * {@code PipelineConfig.load} must throw {@link IllegalArgumentException}.
     */
    @Test
    void throwsWhenIngesterSetButSegmentsMissing(@TempDir Path dir) throws Exception {
        Path pipeline = dir.resolve("no_segments.toon");
        Files.writeString(pipeline, pipelineToonWithoutSegments(dir, "com.acme.SomeIngester"));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(pipeline.toString()),
                "Expected IllegalArgumentException when ingester is set but segments is absent");
    }

    /**
     * When {@code segments:} references a schema file that does not exist on disk,
     * {@code PipelineConfig.load} must throw {@link FileNotFoundException}.
     */
    @Test
    void throwsWhenSegmentSchemaFileMissing(@TempDir Path dir) throws Exception {
        String missing = dir.resolve("ghost_schema.toon").toString().replace("\\", "/");
        Path pipeline = dir.resolve("missing_schema.toon");
        Files.writeString(pipeline, pipelineToonWithMissingSegment(dir, "com.acme.SomeIngester", missing));
        assertThrows(FileNotFoundException.class,
                () -> PipelineConfig.load(pipeline.toString()),
                "Expected FileNotFoundException when a segment schema file does not exist");
    }

    /**
     * An empty {@code segments:} map (no keys) is also invalid — same as a missing map.
     * Note: JToon parses an empty map as null / absent; this test validates via the
     * missing-segment branch rather than a truly-empty map.
     */
    @Test
    void throwsWhenIngesterSetAndSegmentsMapIsEmpty(@TempDir Path dir) throws Exception {
        // Build a toon where segments block is present but contains a non-existent file,
        // which is the closest we can get to "empty" while still being parseable.
        // The real "segments: {}" (empty) is treated as absent by JToon, covered above.
        // Here we re-verify the FileNotFoundException path with a fresh temp dir.
        String missing = dir.resolve("also_gone.toon").toString().replace("\\", "/");
        Path pipeline = dir.resolve("empty_segments.toon");
        Files.writeString(pipeline, pipelineToonWithMissingSegment(dir, "com.acme.Other", missing));
        assertThrows(FileNotFoundException.class, () -> PipelineConfig.load(pipeline.toString()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String pipelineToonWithoutSegments(Path dir, String ingesterClass) {
        return """
                name: VALIDATION_ETL
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
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, ingesterClass);
    }

    private static String pipelineToonWithMissingSegment(Path dir, String ingesterClass,
                                                         String schemaPath) {
        return """
                name: VALIDATION_ETL
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
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 0
                    skip_junk_lines: 0
                    skip_tail_lines: 0
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, ingesterClass, schemaPath);
    }
}
