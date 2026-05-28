package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineConfigBatchTest {

    /** Parsed schema map equivalent of {@link #miniSchema()} — for use without file I/O. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> miniSchemaMap() {
        return Map.of(
                "partitionKey", "EVENT_DATE",
                "raw", Map.of("fields", List.of(
                        Map.of("name", "ID",         "selector", "0", "type", "VARCHAR"),
                        Map.of("name", "AMT",        "selector", "1", "type", "DOUBLE"),
                        Map.of("name", "EVENT_DATE", "selector", "2", "type", "DATE"))),
                "mapping", Map.of("rules", List.of(
                        Map.of("targetColumn", "ID",         "sourceExpression", "ID",         "transformType", "DIRECT"),
                        Map.of("targetColumn", "AMT",        "sourceExpression", "AMT",        "transformType", "DIRECT"),
                        Map.of("targetColumn", "EVENT_DATE", "sourceExpression", "EVENT_DATE", "transformType", "DIRECT"))));
    }

    /** Minimal 3-column schema reused across batch tests. */
    public static String miniSchema() {
        return """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type}:
                ID,"0",VARCHAR
                AMT,"1",DOUBLE
                EVENT_DATE,"2",DATE
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;
    }

    /** Writes a minimal valid pipeline toon into dir; returns its path. batchSection may be "". */
    public static Path writePipeline(Path dir, String batchSection) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, miniSchema());
        String toon = """
            name: MINI_ETL
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
              log_dir: %s/logs
            output:
              format: CSV
            processing:
              threads: 2
              file_pattern: "glob:**/*.{csv,csv.gz}"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
            %s
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"), batchSection);
        Path p = dir.resolve("mini_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void defaultsToSingleFileBatchesWhenSectionAbsent(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        assertEquals(1, cfg.batchMaxFiles);
        assertEquals(Long.MAX_VALUE, cfg.batchMaxBytes);
    }

    @Test
    void readsBatchCaps(@TempDir Path dir) throws Exception {
        String batch = """
              batch:
                max_files: 500
                max_bytes: 268435456
            """;
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, batch).toString());
        assertEquals(500, cfg.batchMaxFiles);
        assertEquals(268435456L, cfg.batchMaxBytes);
    }

    @Test
    void derivesBatchAuditPaths(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        assertNotNull(cfg.batchesFilePath);
        assertNotNull(cfg.lineageFilePath);
        assertNotNull(cfg.manifestsDir);
        assertTrue(cfg.batchesFilePath.contains("_batches_"));
        assertTrue(cfg.lineageFilePath.contains("_lineage_"));
        assertTrue(cfg.manifestsDir.replace("\\", "/").endsWith("manifests"));
    }
}
