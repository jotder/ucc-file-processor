package com.gamma.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal config fixtures for agent-module tests. The agent module can't see the core module's
 * test classes (no shared test-jar), so this replicates the tiny {@code mini} pipeline+schema the
 * core tests use — enough for the metadata catalog to expose {@code source:mini_etl},
 * {@code event:mini_etl/mini} and its columns for {@code explain-entity} to ground on.
 */
public final class AgentTestConfigs {

    private AgentTestConfigs() {}

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

    /** A 4-column schema where ID is authored (MANUAL) and AMT/EVENT_DATE are blank (NONE). */
    public static String describedSchema() {
        return """
            partitionKey: EVENT_DATE
            raw:
              name: mini
              format: CSV
              fields[3]{name,selector,type,description}:
                ID,"0",VARCHAR,"Primary identifier"
                AMT,"1",DOUBLE,""
                EVENT_DATE,"2",DATE,""
            mapping:
              canonicalName: mini
              rawName: mini
              rules[3]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
                AMT,AMT,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """;
    }

    /** Like {@link #writePipeline} but with the {@link #describedSchema()} (ID authored, rest blank). */
    public static Path writePipelineWithDescribedSchema(Path dir) throws Exception {
        Path pipe = writePipeline(dir);
        Files.writeString(dir.resolve("mini_schema.toon"), describedSchema());
        return pipe;
    }

    /** Write {@code mini_pipeline.toon} (+ its schema) under {@code dir}; returns the pipeline path. */
    public static Path writePipeline(Path dir) throws Exception {
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
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path p = dir.resolve("mini_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}
