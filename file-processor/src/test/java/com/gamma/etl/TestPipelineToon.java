package com.gamma.etl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builder for the pipeline / schema {@code .toon} fixture files used by the
 * integration test suite.  Replaces ~5 hand-rolled copy-pasted toon strings
 * scattered across {@code BatchProcessorPluginDeepTest},
 * {@code TypedRecordIngesterTest}, {@code BatchProcessorPluginTest}, etc.
 *
 * <p>Usage:
 * <pre>
 *   PipelineConfig cfg = TestPipelineToon.events(tempDir)
 *       .ingester(MyIngester.class.getName())
 *       .segment("CALL", TestPipelineToon.eventSchema())
 *       .segment("SMS",  TestPipelineToon.eventSchema())
 *       .load();
 * </pre>
 *
 * <p>Keeps a single canonical place to update when the JToon list syntax or
 * the schema layout evolves, so test drift stops here.
 */
public final class TestPipelineToon {

    private final Path dir;
    private final String name;
    private String ingesterClass;
    private final LinkedHashMap<String, String> segments = new LinkedHashMap<>();
    private final Map<String, Object> ingesterConfig = new LinkedHashMap<>();

    private TestPipelineToon(Path dir, String name) {
        this.dir = dir;
        this.name = name;
    }

    /** Construct a builder rooted at {@code dir} with the pipeline name {@code "EVENTS_ETL"}. */
    public static TestPipelineToon events(Path dir) {
        return new TestPipelineToon(dir, "EVENTS_ETL");
    }

    /** Override the pipeline name (default {@code "EVENTS_ETL"}). */
    public TestPipelineToon name(String n) {
        return new TestPipelineToon(this.dir, n).copyState(this);
    }

    private TestPipelineToon copyState(TestPipelineToon other) {
        this.ingesterClass = other.ingesterClass;
        this.segments.putAll(other.segments);
        this.ingesterConfig.putAll(other.ingesterConfig);
        return this;
    }

    /** Set the {@code processing.ingester:} fully-qualified class name. */
    public TestPipelineToon ingester(String fqcn) {
        this.ingesterClass = fqcn;
        return this;
    }

    /**
     * Register a segment.  The {@code schemaToonBody} is written to a file under
     * {@code dir} and referenced from {@code processing.segments:}.
     */
    public TestPipelineToon segment(String key, String schemaToonBody) throws IOException {
        Path schemaFile = dir.resolve(key.toLowerCase() + "_schema.toon");
        Files.writeString(schemaFile, schemaToonBody);
        segments.put(key, schemaFile.toString().replace("\\", "/"));
        return this;
    }

    /** Add a {@code processing.ingester_config:} key. */
    public TestPipelineToon ingesterConfig(String key, Object value) {
        this.ingesterConfig.put(key, value);
        return this;
    }

    /** Write the pipeline toon to {@code dir/events_pipeline.toon} and load it. */
    public PipelineConfig load() throws IOException {
        Path pipeline = dir.resolve("events_pipeline.toon");
        Files.writeString(pipeline, render());
        return PipelineConfig.load(pipeline.toString());
    }

    private String render() {
        StringBuilder b = new StringBuilder();
        b.append("name: ").append(name).append('\n');
        b.append("version: 1\n");
        b.append("dirs:\n");
        b.append("  poll: ").append(dir).append("/inbox\n");
        b.append("  database: ").append(dir).append("/db\n");
        b.append("  backup: ").append(dir).append("/backup\n");
        b.append("  temp: ").append(dir).append("/temp\n");
        b.append("  errors: ").append(dir).append("/errors\n");
        b.append("  quarantine: ").append(dir).append("/quarantine\n");
        b.append("  status_dir: ").append(dir).append("/status\n");
        b.append("  log_dir: ").append(dir).append("/logs\n");
        b.append("output:\n");
        b.append("  format: CSV\n");
        b.append("processing:\n");
        b.append("  threads: 1\n");
        b.append("  file_pattern: \"glob:**/*\"\n");
        if (ingesterClass != null) {
            b.append("  ingester: ").append(ingesterClass).append('\n');
            b.append("  segments:\n");
            for (var e : segments.entrySet())
                b.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            if (!ingesterConfig.isEmpty()) {
                b.append("  ingester_config:\n");
                for (var e : ingesterConfig.entrySet())
                    b.append("    ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        b.append("  csv_settings:\n");
        b.append("    delimiter: \",\"\n");
        b.append("    skip_header_lines: 0\n");
        b.append("    skip_junk_lines: 0\n");
        b.append("    skip_tail_lines: 0\n");
        b.append("    date_formats[1]: \"%Y-%m-%d\"\n");
        b.append("    timestamp_formats[1]: \"%Y-%m-%d\"\n");
        return b.toString();
    }

    // ── canonical event schemas ───────────────────────────────────────────────

    /**
     * Standard CALL/SMS-style event schema used by most plugin tests: ID + EVENT_DATE,
     * with {@code event_type / year / month / day} partition columns.
     */
    public static String eventSchema(String segmentName, String extraField, String extraFieldType) {
        return """
                partitions[4]{column,source,type}:
                  event_type,EVENT_TYPE,VARCHAR
                  year,EVENT_DATE,DATE_YEAR
                  month,EVENT_DATE,DATE_MONTH
                  day,EVENT_DATE,DATE_DAY
                raw:
                  name: %s
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID,"0",VARCHAR
                    EVENT_DATE,"1",DATE
                    %s,"2",%s
                mapping:
                  canonicalName: %s
                  rawName: %s
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                    %s,%s,DIRECT
                """.formatted(segmentName, extraField, extraFieldType,
                segmentName, segmentName, extraField, extraField);
    }
}
