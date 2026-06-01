package com.gamma.etl;

import com.gamma.api.PublicApi;
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
 * <p>Configuration is grouped into six nested records by concern, reached through
 * accessor methods: {@link #identity()}, {@link #dirs()}, {@link #processing()},
 * {@link #csv()}, {@link #output()}, {@link #schemas()}. For example the output
 * database directory is {@code cfg.dirs().database()} and the batch-concurrency cap
 * is {@code cfg.processing().threads()}.
 *
 * <p>(Prior to 2.0 these were ~30 flat {@code public final} fields, e.g.
 * {@code cfg.databaseDir}. The nested grouping is the one breaking API change of
 * 2.0; pipeline {@code .toon} configs and on-disk output are unchanged.)
 *
 * <p>Instances are created via the {@link #load(String)} static factory, which
 * validates the config, resolves schemas, and computes the run timestamp. The
 * object is safe for concurrent read access by all worker threads once
 * {@code load()} returns.
 */
@PublicApi(since = "1.0.0")
public final class PipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(PipelineConfig.class);

    // ── nested config groups ───────────────────────────────────────────────────

    /** Pipeline identity: original name, normalised name, and the run timestamp. */
    @PublicApi(since = "2.0.0")
    public record Identity(String name, String pipelineName, String runTimestamp) {}

    /**
     * All filesystem paths for the run. {@code statusFilePath}/{@code batchesFilePath}/
     * {@code lineageFilePath}/{@code manifestsDir} are {@code null} when status is disabled.
     */
    @PublicApi(since = "2.0.0")
    public record Dirs(String poll, String database, String backup, String temp,
                       String errors, String quarantine, String markers, String logDir,
                       String statusFilePath, String batchesFilePath, String lineageFilePath,
                       String manifestsDir, String commitLogPath) {}

    /**
     * Execution controls. {@code threads} caps concurrent batches (semaphore permits
     * over a virtual-thread executor; default 4); {@code duckdbThreads} caps each batch
     * connection's DuckDB parallelism via {@code PRAGMA threads} ({@code 0} = DuckDB
     * default). Set so {@code threads × duckdbThreads ≈ cores} to avoid oversubscription.
     */
    @PublicApi(since = "2.0.0")
    public record Processing(int threads, int duckdbThreads, String filePattern,
                             int batchMaxFiles, long batchMaxBytes,
                             boolean duplicateCheckEnabled, String markerExtension,
                             int retentionDays) {}

    /**
     * Delimited-text parse settings. {@code engine} is {@code "auto"}/{@code "duckdb"}/
     * {@code "java"} — {@code auto} uses DuckDB's native reader for clean configs and the
     * Java parser otherwise (see {@link DuckDbCsvIngester#usesDuckDb}).
     */
    @PublicApi(since = "2.0.0")
    public record CsvSettings(String delimiter, int skipHeaderLines, int skipJunkLines,
                              int skipTailLines, int skipTailCols, boolean hasHeader,
                              String engine, List<String> dateFormats, List<String> tsFormats) {}

    /** Output format/compression and the optional {@code output.ducklake} map ({@code null} if absent). */
    @PublicApi(since = "2.0.0")
    public record Output(String format, String compression, Map<String, Object> duckLake) {}

    /**
     * Schema resolution — exactly one of {@code selector} (multi-schema {@code schemas[]}),
     * {@code single} (legacy {@code schema_file}), or {@code segments} (plugin path) is
     * non-null. {@code ingesterClass} is the plugin FQCN ({@code null} for built-in CSV);
     * {@code ingesterConfig} is the plugin's free-form settings map (empty, never null).
     */
    @PublicApi(since = "2.0.0")
    public record Schemas(SchemaSelector selector, Map<String, Object> single,
                          LinkedHashMap<String, Map<String, Object>> segments,
                          String ingesterClass, Map<String, Object> ingesterConfig) {}

    /**
     * Optional DuckDB engine-resource controls (additive, 3.10.0). All {@code null}/blank ⇒
     * DuckDB defaults — fully backward-compatible. Parsed from {@code processing.duckdb}.
     *
     * <p>{@code tempDirectory} relocates the per-batch temp database <em>and</em> DuckDB's spill
     * scratch. The engine defaults it to {@code dirs.temp} (on the data volume) rather than the
     * JVM/system temp dir ({@code /tmp}), so a huge file's scratch never lands on a small
     * {@code /tmp}. {@code memoryLimit} and {@code maxTempDirectorySize} accept DuckDB size
     * strings (e.g. {@code "16GB"}); the latter caps spill so a runaway query fails fast instead
     * of filling the disk.
     */
    @PublicApi(since = "3.10.0")
    public record DuckDbSettings(String memoryLimit, String tempDirectory,
                                 String maxTempDirectorySize) {}

    /**
     * Optional large-file auto-chunking (additive, 3.10.0). Parsed from {@code processing.chunking}.
     *
     * <p>{@code maxFileBytes <= 0} disables chunking (the default — behaviour unchanged). When a
     * single input file exceeds {@code maxFileBytes}, the CSV ingester streams it into bounded
     * chunks of ~{@code targetChunkBytes} (defaulting to {@code maxFileBytes} when unset), so peak
     * scratch stays bounded per chunk and chunks process concurrently — instead of materialising
     * one multi-hundred-GB unit.
     */
    @PublicApi(since = "3.10.0")
    public record Chunking(long maxFileBytes, long targetChunkBytes) {
        /** Effective per-chunk target, defaulting to the threshold when unset. */
        public long effectiveChunkBytes() {
            return targetChunkBytes > 0 ? targetChunkBytes : maxFileBytes;
        }
        /** Whether chunking is enabled for a file of {@code fileBytes}. */
        public boolean appliesTo(long fileBytes) {
            return maxFileBytes > 0 && fileBytes > maxFileBytes;
        }
    }

    // ── grouped state + accessors ──────────────────────────────────────────────

    private final Identity   identity;
    private final Dirs       dirs;
    private final Processing processing;
    private final CsvSettings csv;
    private final Output     output;
    private final Schemas    schemas;
    private final DuckDbSettings duckdb;
    private final Chunking       chunking;

    /**
     * The {@code status_dir} to create in {@link #prepare()} ({@code null} when status is disabled or
     * a literal {@code status_file} was used). Holding it here keeps {@link #fromMap} free of the one
     * filesystem side-effect that {@code load} historically performed inline.
     */
    private final String statusDirToPrepare;

    public Identity   identity()   { return identity; }
    public Dirs       dirs()       { return dirs; }
    public Processing processing() { return processing; }
    public CsvSettings csv()       { return csv; }
    public Output     output()     { return output; }
    public Schemas    schemas()    { return schemas; }
    /** Optional DuckDB resource controls; never null (fields may be null ⇒ DuckDB defaults). */
    public DuckDbSettings duckdb()   { return duckdb; }
    /** Optional large-file chunking config; never null ({@code maxFileBytes <= 0} ⇒ disabled). */
    public Chunking       chunking() { return chunking; }

    // ── private constructor — use load() ──────────────────────────────────────

    private PipelineConfig(Builder b) {
        this.identity = new Identity(b.name, b.pipelineName, b.runTimestamp);
        this.dirs = new Dirs(b.pollDir, b.databaseDir, b.backupDir, b.tempDir, b.errorsDir,
                b.quarantineDir, b.markersDir, b.logDir, b.statusFilePath,
                b.batchesFilePath, b.lineageFilePath, b.manifestsDir, b.commitLogPath);
        this.processing = new Processing(b.threads, b.duckdbThreads, b.filePattern,
                b.batchMaxFiles, b.batchMaxBytes, b.duplicateCheckEnabled,
                b.markerExtension, b.retentionDays);
        this.csv = new CsvSettings(b.delimiter, b.skipHeaderLines, b.skipJunkLines,
                b.skipTailLines, b.skipTailCols, b.hasHeader, b.csvEngine,
                Collections.unmodifiableList(b.dateFormats),
                Collections.unmodifiableList(b.tsFormats));
        this.output = new Output(b.outputFormat, b.compression, b.duckLakeCfg);
        this.schemas = new Schemas(b.schemaSelector, b.singleSchema, b.segmentSchemas,
                b.ingesterClass,
                b.ingesterConfig != null
                        ? Collections.unmodifiableMap(b.ingesterConfig)
                        : Collections.emptyMap());
        this.duckdb   = new DuckDbSettings(b.duckMemoryLimit, b.duckTempDirectory, b.duckMaxTempSize);
        this.chunking = new Chunking(b.chunkMaxFileBytes, b.chunkTargetBytes);
        this.statusDirToPrepare = b.statusDirToPrepare;
    }

    // ── static factory ────────────────────────────────────────────────────────

    /**
     * Parse the pipeline {@code .toon} file at {@code configPath}, validate all
     * directories, load schema(s), and return an immutable {@code PipelineConfig}.
     *
     * <p>Equivalent to {@code fromMap(ToonHelper.load(configPath)).prepare()} — it decodes the file,
     * builds the (pure) config, then performs the one filesystem side-effect (creating the status
     * directory). Splitting those steps lets a draft be parsed/validated from memory with no I/O via
     * {@link #fromMap(Map)}.
     *
     * @param configPath filesystem path to the pipeline {@code .toon} file
     * @throws FileNotFoundException if the pipeline or any referenced schema file is missing
     * @throws IOException           on any I/O or parse failure
     * @throws IllegalArgumentException if any managed directory is nested inside the poll dir
     */
    public static PipelineConfig load(String configPath) throws IOException {
        PipelineConfig cfg = fromMap(ToonHelper.load(configPath), configPath);
        cfg.prepare();
        return cfg;
    }

    /**
     * Build an immutable {@code PipelineConfig} from an already-decoded config map — a <b>pure</b>
     * parse: it resolves and validates schemas and directories but performs no directory creation
     * (call {@link #prepare()} for that). This is the entry point for validating a draft that has
     * never been written to disk.
     *
     * @throws IOException if a referenced schema file is missing or unreadable
     * @throws IllegalArgumentException on any structural/validation problem
     */
    public static PipelineConfig fromMap(Map<String, Object> raw) throws IOException {
        return fromMap(raw, "<config>");
    }

    /**
     * Create the run's status directory — the single filesystem side-effect formerly performed
     * inline during {@code load}. A no-op when status is disabled or a literal {@code status_file}
     * was configured. Idempotent and safe to call more than once.
     */
    public void prepare() throws IOException {
        if (statusDirToPrepare != null && !statusDirToPrepare.isBlank()) {
            Files.createDirectories(Paths.get(statusDirToPrepare));
        }
    }

    @SuppressWarnings("unchecked")
    private static PipelineConfig fromMap(Map<String, Object> raw, String sourceLabel) throws IOException {
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
            // Defer the directory creation to prepare(); fromMap stays a pure parse.
            b.statusDirToPrepare = statusDir;
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
            // Commit log is persistent (NOT run-timestamped): a single append-only
            // ledger that accumulates committed batches across every run of this
            // pipeline — the durable source of truth for "did this batch finish".
            b.commitLogPath = statusParent.resolve(b.pipelineName + "_commits.log").toString();
        }

        validateDirs(sourceLabel, b.pollDir, dirs);

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

        // ── DuckDB engine-resource controls (additive, optional) ───────────────
        // Defaults (all absent) preserve DuckDB's own defaults; tempDirectory falls back to
        // dirs.temp at the call site so scratch lands on the data volume, never the system /tmp.
        Map<String, Object> duck = (Map<String, Object>) proc.get("duckdb");
        if (duck != null) {
            b.duckMemoryLimit   = blankToNull(duck.get("memory_limit"));
            b.duckTempDirectory = blankToNull(duck.get("temp_directory"));
            b.duckMaxTempSize   = blankToNull(duck.get("max_temp_directory_size"));
        }

        // ── large-file auto-chunking (additive, optional; disabled by default) ──
        Map<String, Object> chunk = (Map<String, Object>) proc.get("chunking");
        if (chunk != null) {
            b.chunkMaxFileBytes = toLong(chunk.get("max_file_bytes"));
            b.chunkTargetBytes  = toLong(chunk.get("target_chunk_bytes"));
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

    /** Parse a size/count to long; {@code null}/blank ⇒ 0. Accepts plain digits (bytes). */
    private static long toLong(Object v) {
        if (v == null) return 0;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? 0 : Long.parseLong(s);
    }

    /** Trim a config value to a String; {@code null}/blank ⇒ {@code null}. */
    private static String blankToNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
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
        String statusDirToPrepare;
        int    threads       = 4;
        int    duckdbThreads = 0;
        String filePattern   = "glob:**/*.{csv,csv.gz}";
        int    batchMaxFiles   = 1;
        long   batchMaxBytes   = Long.MAX_VALUE;
        String duckMemoryLimit;
        String duckTempDirectory;
        String duckMaxTempSize;
        long   chunkMaxFileBytes = 0;
        long   chunkTargetBytes  = 0;
        String batchesFilePath;
        String lineageFilePath;
        String manifestsDir;
        String commitLogPath;
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
