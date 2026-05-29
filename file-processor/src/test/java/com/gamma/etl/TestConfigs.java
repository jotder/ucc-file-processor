package com.gamma.etl;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fluent builder for {@link PipelineConfig} instances in tests, so individual
 * test files don't each hand-roll a pipeline toon (the duplication called out as
 * gap M5). Writes a single-schema CSV pipeline toon to a temp dir and loads it.
 *
 * <pre>
 *   PipelineConfig cfg = TestConfigs.csv(dir, schemaToon)
 *       .skipJunk(13).skipTail(2).hasHeader(true)
 *       .load();
 * </pre>
 */
public final class TestConfigs {

    private final Path dir;
    private final String schemaToon;
    private String  format        = "CSV";
    private String  compression   = null;
    private String  delimiter     = ",";
    private int     skipHeader    = 0;
    private int     skipJunk      = 0;
    private int     skipTail      = 0;
    private int     skipTailCols  = 0;
    private boolean hasHeader     = true;
    private String  dateFormats   = "\"%Y-%m-%d\"";
    private String  tsFormats     = "\"%Y-%m-%d\"";
    private boolean duplicateCheck = true;

    private TestConfigs(Path dir, String schemaToon) {
        this.dir = dir;
        this.schemaToon = schemaToon;
    }

    public static TestConfigs csv(Path dir, String schemaToon) {
        return new TestConfigs(dir, schemaToon);
    }

    public TestConfigs format(String f)        { this.format = f; return this; }
    public TestConfigs compression(String c)   { this.compression = c; return this; }
    public TestConfigs delimiter(String d)     { this.delimiter = d; return this; }
    public TestConfigs skipHeader(int n)       { this.skipHeader = n; return this; }
    public TestConfigs skipJunk(int n)         { this.skipJunk = n; return this; }
    public TestConfigs skipTail(int n)         { this.skipTail = n; return this; }
    public TestConfigs skipTailCols(int n)     { this.skipTailCols = n; return this; }
    public TestConfigs hasHeader(boolean b)    { this.hasHeader = b; return this; }
    /** Comma-separated, quoted strptime patterns, e.g. {@code "\"%Y-%m-%d %H:%M:%S\", \"%Y-%m-%d\""}. */
    public TestConfigs dateFormats(String csv) { this.dateFormats = csv; return this; }
    public TestConfigs tsFormats(String csv)   { this.tsFormats = csv; return this; }
    public TestConfigs duplicateCheck(boolean b) { this.duplicateCheck = b; return this; }

    public PipelineConfig load() throws Exception {
        Path schema = dir.resolve("schema_" + Integer.toHexString(System.identityHashCode(this)) + ".toon");
        Files.writeString(schema, schemaToon);

        int dateCount = dateFormats.split(",").length;
        int tsCount   = tsFormats.split(",").length;
        String compLine = compression != null ? "  compression: " + compression + "\n" : "";

        String toon = """
                name: TEST_ETL
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
                  format: %s
                %sprocessing:
                  threads: 1
                  file_pattern: "glob:**/*"
                  duplicate_check:
                    enabled: %s
                    marker_extension: .processed
                    retention_days: 90
                  schema_file: "%s"
                  csv_settings:
                    delimiter: "%s"
                    has_header: %s
                    skip_header_lines: %d
                    skip_junk_lines: %d
                    skip_tail_lines: %d
                    skip_tail_columns: %d
                    date_formats[%d]: %s
                    timestamp_formats[%d]: %s
                """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                format, compLine, duplicateCheck,
                schema.toString().replace("\\", "/"),
                delimiter, hasHeader, skipHeader, skipJunk, skipTail, skipTailCols,
                dateCount, dateFormats, tsCount, tsFormats);

        Path p = dir.resolve("pipeline_" + Integer.toHexString(System.identityHashCode(this)) + ".toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }
}
