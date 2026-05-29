package com.gamma.etl;

import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Immutable configuration object for one ETL pipeline run.
 *
 * <p>Replaces the static fields that were scattered across
 * {@link com.gamma.inspector.SourceProcessor}.  All ETL utility classes
 * ({@link CsvIngester}, {@link DataTransformer}, {@link MarkerManager}, etc.)
 * receive a single {@code PipelineConfig} and access what they need through
 * its public final fields.
 *
 * <p>Instances are created via the {@link #load(String)} static factory, which
 * validates the config, resolves schemas, and computes the run timestamp.
 *
 * <p>The object is safe for concurrent read access by all worker threads once
 * {@code load()} returns.
 */
public final class PipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfig.class);

    // ── pipeline identity ─────────────────────────────────────────────────────

    /** Original {@code name:} value from the toon (e.g. {@code "VOUCHER_UNKNOWN_ETL"}). */
    public final String name;
    /** Lowercase, space-to-underscore normalised name, used as filename prefix. */
    public final String pipelineName;
    /** Run-scoped timestamp — {@code yyyyMMdd_HHmmss} — shared by status and log filenames. */
    public final String runTimestamp;

    // ── dirs ──────────────────────────────────────────────────────────────────

    public final String pollDir;
    public final String databaseDir;
    public final String backupDir;
    public final String tempDir;
    public final String errorsDir;
    public final String quarantineDir;
    public final String markersDir;
    public final String logDir;
    /** Absolute path to the status CSV for this run; {@code null} = status disabled. */
    public final String statusFilePath;

    // ── processing ────────────────────────────────────────────────────────────

    /**
     * Max batches processed concurrently within one source run (semaphore permits
     * over a virtual-thread executor). Read from {@code processing.threads}, default 4.
     */
    public final int    threads;
    /**
     * Per-worker DuckDB thread cap applied as {@code PRAGMA threads=N} on each batch
     * connection. Read from {@code processing.duckdb_threads}; {@code 0} (default)
     * leaves DuckDB's own default (all cores). Since {@link #threads} batches run
     * concurrently and each opens its own connection, set this so
     * {@code threads × duckdb_threads ≈ cores} to avoid CPU oversubscription.
     */
    public final int    duckdbThreads;
    public final String filePattern;

    // ── batch ─────────────────────────────────────────────────────────────────

    /** Max member files per batch (default 1 → one file per batch, legacy behavior). */
    public final int  batchMaxFiles;
    /** Max summed input bytes per batch (default Long.MAX_VALUE). */
    public final long batchMaxBytes;
    /** Path to the run-scoped batches summary CSV; {@code null} when status is disabled. */
    public final String batchesFilePath;
    /** Path to the run-scoped lineage (count-matrix) CSV; {@code null} when status is disabled. */
    public final String lineageFilePath;
    /** Directory for per-batch JSON manifests; {@code null} when status is disabled. */
    public final String manifestsDir;

    // ── duplicate check ───────────────────────────────────────────────────────

    public final boolean duplicateCheckEnabled;
    public final String  markerExtension;
    public final int     retentionDays;

    // ── csv settings ──────────────────────────────────────────────────────────

    public final String       delimiter;
    public final int          skipHeaderLines;
    public final int          skipJunkLines;
    public final int          skipTailLines;
    public final int          skipTailCols;
    public final boolean      hasHeader;
    public final List<String> dateFormats;
    public final List<String> tsFormats;
    /**
     * CSV parse engine: {@code "auto"} (default), {@code "duckdb"}, or {@code "java"}.
     * Read from {@code csv_settings.engine}. {@code auto} uses DuckDB's native
     * vectorized reader for clean configs (no skip_junk/tail/tail_columns) and the
     * Java parser otherwise. See {@link DuckDbCsvIngester#usesDuckDb}.
     */
    public final String       csvEngine;

    // ── output ────────────────────────────────────────────────────────────────

    public final String outputFormat;
    public final String compression;
    /** The {@code output.ducklake} map, or {@code null} when DuckLake is not configured. */
    public final Map<String, Object> duckLakeCfg;

    // ── schemas ───────────────────────────────────────────────────────────────

    /**
     * Multi-schema selector (non-null when {@code processing.schemas[]} is configured).
     * Exactly one of {@code schemaSelector} / {@code singleSchema} / {@code segmentSchemas}
     * is non-null.
     */
    public final SchemaSelector schemaSelector;

    /**
     * Legacy single-schema config (non-null when {@code processing.schema_file} is used).
     * Exactly one of {@code schemaSelector} / {@code singleSchema} / {@code segmentSchemas}
     * is non-null.
     */
    public final Map<String, Object> singleSchema;

    // ── plugin ingester ───────────────────────────────────────────────────────

    /**
     * Fully-qualified class name of a {@link FileIngester} implementation.
     * {@code null} when the built-in {@code CsvIngester} is used.
     */
    public final String ingesterClass;

    /**
     * Ordered map of segment key → loaded schema config, populated when
     * {@code processing.ingester} is set.  Keys match the entries in
     * {@code processing.segments:} in the pipeline toon.
     * {@code null} when {@code ingesterClass} is {@code null}.
     */
    public final LinkedHashMap<String, Map<String, Object>> segmentSchemas;

    /**
     * Free-form configuration map for the plugin ingester, read from the
     * {@code processing.ingester_config:} block in the pipeline toon.  Empty
     * (never {@code null}) when no such block is present.
     *
     * <p>This is where binary / fixed-width / proprietary plugins put format-specific
     * settings — {@code record_length}, {@code byte_order}, {@code encoding}, etc. —
     * instead of overloading {@code csv_settings}.  Plugins read it via
     * {@code cfg.ingesterConfig.get("my_key")}.
     */
    public final Map<String, Object> ingesterConfig;

    // ── private constructor — use load() ──────────────────────────────────────

    private PipelineConfig(Builder b) {
        this.name                 = b.name;
        this.pipelineName         = b.pipelineName;
        this.runTimestamp         = b.runTimestamp;
        this.pollDir              = b.pollDir;
        this.databaseDir          = b.databaseDir;
        this.backupDir            = b.backupDir;
        this.tempDir              = b.tempDir;
        this.errorsDir            = b.errorsDir;
        this.quarantineDir        = b.quarantineDir;
        this.markersDir           = b.markersDir;
        this.logDir               = b.logDir;
        this.statusFilePath       = b.statusFilePath;
        this.threads              = b.threads;
        this.duckdbThreads        = b.duckdbThreads;
        this.filePattern          = b.filePattern;
        this.batchMaxFiles        = b.batchMaxFiles;
        this.batchMaxBytes        = b.batchMaxBytes;
        this.batchesFilePath      = b.batchesFilePath;
        this.lineageFilePath      = b.lineageFilePath;
        this.manifestsDir         = b.manifestsDir;
        this.duplicateCheckEnabled= b.duplicateCheckEnabled;
        this.markerExtension      = b.markerExtension;
        this.retentionDays        = b.retentionDays;
        this.delimiter            = b.delimiter;
        this.skipHeaderLines      = b.skipHeaderLines;
        this.skipJunkLines        = b.skipJunkLines;
        this.skipTailLines        = b.skipTailLines;
        this.skipTailCols         = b.skipTailCols;
        this.hasHeader            = b.hasHeader;
        this.csvEngine            = b.csvEngine;
        this.dateFormats          = Collections.unmodifiableList(b.dateFormats);
        this.tsFormats            = Collections.unmodifiableList(b.tsFormats);
        this.outputFormat         = b.outputFormat;
        this.compression          = b.compression;
        this.duckLakeCfg          = b.duckLakeCfg;
        this.schemaSelector       = b.schemaSelector;
        this.singleSchema         = b.singleSchema;
        this.ingesterClass        = b.ingesterClass;
        this.segmentSchemas       = b.segmentSchemas;
        this.ingesterConfig       = b.ingesterConfig != null
                ? Collections.unmodifiableMap(b.ingesterConfig)
                : Collections.emptyMap();
    }

    // ── static factory ────────────────────────────────────────────────────────

    /**
     * Parse the pipeline {@code .toon} file at {@code configPath}, validate all
     * directories, load schema(s), and return an immutable {@code PipelineConfig}.
     *
     * @param configPath filesystem path to the pipeline {@code .toon} file
     * @throws FileNotFoundException if the pipeline or any referenced schema file is missing
     * @throws IOException           on any I/O or parse failure
     * @throws IllegalArgumentException if any managed directory is nested inside the poll dir
     */
    @SuppressWarnings("unchecked")
    public static PipelineConfig load(String configPath) throws IOException {
        Map<String, Object> raw = ToonHelper.load(configPath);
        Builder b = new Builder();

        // ── identity ──────────────────────────────────────────────────────────
        b.name          = String.valueOf(raw.get("name"));
        b.pipelineName  = b.name.toLowerCase().replace(' ', '_');
        b.runTimestamp  = LocalDateTime.now()
                                       .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // ── dirs ──────────────────────────────────────────────────────────────
        Map<String, Object> dirs = ToonHelper.requireSection(raw, "dirs");
        b.pollDir       = require(dirs, "poll");
        b.databaseDir   = require(dirs, "database");
        b.backupDir     = (String) dirs.get("backup");
        b.tempDir       = (String) dirs.get("temp");
        b.errorsDir     = opt(dirs, "errors",
                                  b.pollDir + "/errors");
        b.quarantineDir = opt(dirs, "quarantine",
                                  b.pollDir + "/quarantine");
        b.markersDir    = (String) dirs.get("markers");
        b.logDir        = (String) dirs.get("log_dir");

        // status file path: status_dir (new) → timestamped filename; status_file (legacy) → literal
        String statusDir = (String) dirs.get("status_dir");
        if (statusDir != null && !statusDir.isBlank()) {
            Files.createDirectories(Paths.get(statusDir));
            b.statusFilePath = Paths.get(statusDir,
                    b.pipelineName + "_status_" + b.runTimestamp + ".csv").toString();
        } else {
            b.statusFilePath = (String) dirs.get("status_file");
        }

        // ── batch audit + manifest paths (sibling to the status CSV) ──────────────
        if (b.statusFilePath != null && !b.statusFilePath.isBlank()) {
            Path statusParent = Paths.get(b.statusFilePath).toAbsolutePath().getParent();
            b.batchesFilePath = statusParent.resolve(
                    b.pipelineName + "_batches_" + b.runTimestamp + ".csv").toString();
            b.lineageFilePath = statusParent.resolve(
                    b.pipelineName + "_lineage_" + b.runTimestamp + ".csv").toString();
            b.manifestsDir = statusParent.resolve("manifests").toString();
        }

        validateDirs(configPath, b.pollDir, dirs);

        // ── processing ────────────────────────────────────────────────────────
        Map<String, Object> proc = ToonHelper.requireSection(raw, "processing");
        b.threads       = toInt(proc.getOrDefault("threads", 4));
        b.duckdbThreads = toInt(proc.getOrDefault("duckdb_threads", 0));
        b.filePattern   = opt(proc, "file_pattern", "glob:**/*.{csv,csv.gz}");

        // ── batch caps ──────────────────────────────────────────────────────────
        Map<String, Object> batch = (Map<String, Object>) proc.get("batch");
        if (batch != null) {
            b.batchMaxFiles = toInt(batch.getOrDefault("max_files", 1));
            Object mb = batch.get("max_bytes");
            b.batchMaxBytes = (mb == null) ? Long.MAX_VALUE : Long.parseLong(String.valueOf(mb));
        }

        // ── duplicate check ───────────────────────────────────────────────────
        Map<String, Object> dup = (Map<String, Object>) proc.get("duplicate_check");
        if (dup != null) {
            b.duplicateCheckEnabled = Boolean.parseBoolean(String.valueOf(dup.get("enabled")));
            b.markerExtension       = opt(dup, "marker_extension", ".processed");
            b.retentionDays         = toInt(dup.getOrDefault("retention_days", 90));
        }

        // ── csv settings ──────────────────────────────────────────────────────
        Map<String, Object> csv = (Map<String, Object>) proc.get("csv_settings");
        if (csv != null) {
            b.delimiter       = opt(csv, "delimiter", ",");
            b.skipHeaderLines = toInt(csv.getOrDefault("skip_header_lines", 0));
            b.skipJunkLines   = toInt(csv.getOrDefault("skip_junk_lines",   0));
            b.skipTailLines   = toInt(csv.getOrDefault("skip_tail_lines",   0));
            b.skipTailCols    = toInt(csv.getOrDefault("skip_tail_columns", 0));
            b.hasHeader       = Boolean.parseBoolean(
                                    String.valueOf(csv.getOrDefault("has_header", "true")));
            b.csvEngine       = String.valueOf(csv.getOrDefault("engine", "auto")).toLowerCase();
            if (csv.get("date_formats")      instanceof List<?> df)
                b.dateFormats = (List<String>) df;
            if (csv.get("timestamp_formats") instanceof List<?> tf)
                b.tsFormats   = (List<String>) tf;
        }

        // ── output ────────────────────────────────────────────────────────────
        Map<String, Object> out = (Map<String, Object>) raw.get("output");
        if (out != null) {
            b.outputFormat = String.valueOf(out.getOrDefault("format", "CSV")).toUpperCase();
            b.compression  = (String) out.get("compression");
            b.duckLakeCfg  = (Map<String, Object>) out.get("ducklake");
        }

        // ── plugin ingester + segments ────────────────────────────────────────
        b.ingesterClass = (String) proc.get("ingester");
        Object icfg = proc.get("ingester_config");
        if (icfg instanceof Map<?, ?> icfgMap)
            b.ingesterConfig = (Map<String, Object>) icfgMap;
        if (b.ingesterClass != null && !b.ingesterClass.isBlank()) {
            Object segsRaw = proc.get("segments");
            if (!(segsRaw instanceof Map<?,?> segsMap) || segsMap.isEmpty())
                throw new IllegalArgumentException(
                        "processing.segments must be a non-empty map when processing.ingester is set");
            b.segmentSchemas = new LinkedHashMap<>();
            for (var entry : ((Map<?,?>) segsRaw).entrySet()) {
                String key        = (String) entry.getKey();
                String schemaPath = (String) entry.getValue();
                if (!Files.exists(Paths.get(schemaPath)))
                    throw new FileNotFoundException("Segment schema not found for '" + key + "': " + schemaPath);
                Map<String, Object> schema = (Map<String, Object>)
                        JToon.decode(Files.readString(Paths.get(schemaPath), StandardCharsets.UTF_8));
                Identifiers.validateSchema(schema, "segment[" + key + "]");
                b.segmentSchemas.put(key, schema);
            }
            log.info("[CONFIG] Plugin ingester: {}  segments: {}",
                    b.ingesterClass, b.segmentSchemas.keySet());
        }

        // ── schemas ───────────────────────────────────────────────────────────
        // Plugin ingester path: schemas already loaded into segmentSchemas above; skip.
        List<Map<String, Object>> schemaDefs = (List<Map<String, Object>>) proc.get("schemas");
        if (b.ingesterClass != null && !b.ingesterClass.isBlank()) {
            // no-op — segment schemas were loaded above
        } else if (schemaDefs != null && !schemaDefs.isEmpty()) {
            LinkedHashMap<Integer, Map<String, Object>> byCount   = new LinkedHashMap<>();
            LinkedHashMap<Integer, PathMatcher>         byPattern = new LinkedHashMap<>();
            LinkedHashMap<Integer, String>              byTable   = new LinkedHashMap<>();

            for (Map<String, Object> entry : schemaDefs) {
                int    colCount    = toInt(entry.get("column_count"));
                String schemaPath  = (String) entry.get("schema_file");
                String table       = (String) entry.get("table");
                String filePattern = (String) entry.get("file_pattern");

                if (!Files.exists(Paths.get(schemaPath)))
                    throw new FileNotFoundException("Schema file not found: " + schemaPath);
                Map<String, Object> schemaCfg = (Map<String, Object>)
                        JToon.decode(Files.readString(Paths.get(schemaPath), StandardCharsets.UTF_8));
                Identifiers.validateSchema(schemaCfg, "schemas[col=" + colCount + "]");
                if (table != null && !table.isBlank())
                    Identifiers.validate(table, "schemas[col=" + colCount + "].table");

                SchemaSelector.register(byCount, byPattern, byTable,
                        colCount, filePattern, schemaCfg, table);
            }

            b.schemaSelector = new SchemaSelector(
                    byCount, byPattern, byTable,
                    b.delimiter, b.skipHeaderLines);

            log.info("[CONFIG] Loaded {} schema(s): col counts {}",
                    schemaDefs.size(),
                    byCount.keySet().stream().map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(", ")));
        } else {
            // Legacy single-schema
            String schemaPath = (String) proc.get("schema_file");
            if (!Files.exists(Paths.get(schemaPath)))
                throw new FileNotFoundException("Schema file not found: " + schemaPath);
            b.singleSchema = (Map<String, Object>)
                    JToon.decode(Files.readString(Paths.get(schemaPath), StandardCharsets.UTF_8));
            Identifiers.validateSchema(b.singleSchema, "schema_file");
        }

        log.info("[CONFIG] Status file : {}", b.statusFilePath);
        PipelineConfig cfg = new PipelineConfig(b);
        ConfigValidator.validate(cfg);  // non-fatal: logs warnings for suspicious-but-legal patterns
        return cfg;
    }

    // ── dir validation ────────────────────────────────────────────────────────

    private static void validateDirs(String configPath, String pollDir,
                                     Map<String, Object> dirs) {
        java.nio.file.Path poll = Paths.get(pollDir).toAbsolutePath().normalize();
        for (String key : new String[]{"database", "backup", "temp", "errors", "quarantine", "markers"}) {
            Object val = dirs.get(key);
            if (val == null) continue;
            java.nio.file.Path dir = Paths.get(val.toString()).toAbsolutePath().normalize();
            if (dir.startsWith(poll))
                throw new IllegalArgumentException(String.format(
                        "Config error in %s: dirs.%s (%s) must be outside the poll directory (%s)",
                        configPath, key, dir, poll));
        }
    }

    // ── tiny helpers ──────────────────────────────────────────────────────────

    private static String require(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException("Missing required dirs." + key);
        return v.toString();
    }

    private static String opt(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : def;
    }

    private static int toInt(Object v) {
        return Integer.parseInt(String.valueOf(v));
    }

    // ── builder (private, only used inside load()) ────────────────────────────

    private static final class Builder {
        String name          = "";
        String pipelineName  = "";
        String runTimestamp  = "";
        String pollDir       = "";
        String databaseDir   = "";
        String backupDir;
        String tempDir;
        String errorsDir     = "";
        String quarantineDir = "";
        String markersDir;
        String logDir;
        String statusFilePath;
        int    threads       = 4;
        int    duckdbThreads = 0;
        String filePattern   = "glob:**/*.{csv,csv.gz}";
        int    batchMaxFiles   = 1;
        long   batchMaxBytes   = Long.MAX_VALUE;
        String batchesFilePath;
        String lineageFilePath;
        String manifestsDir;
        boolean duplicateCheckEnabled = false;
        String  markerExtension       = ".processed";
        int     retentionDays         = 90;
        String       delimiter       = ",";
        int          skipHeaderLines = 0;
        int          skipJunkLines   = 0;
        int          skipTailLines   = 0;
        int          skipTailCols    = 0;
        boolean      hasHeader       = true;
        String       csvEngine       = "auto";
        List<String> dateFormats     = new ArrayList<>();
        List<String> tsFormats       = new ArrayList<>();
        String outputFormat  = "CSV";
        String compression;
        Map<String, Object> duckLakeCfg;
        SchemaSelector      schemaSelector;
        Map<String, Object> singleSchema;
        String ingesterClass;
        LinkedHashMap<String, Map<String, Object>> segmentSchemas;
        Map<String, Object> ingesterConfig;
    }
}
