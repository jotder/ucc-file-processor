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
     * connection's DuckDB parallelism via {@code PRAGMA threads}. The default {@code 0}
     * <em>auto-derives</em> {@code max(1, cores / threads)} so concurrent batches divide the
     * cores instead of each grabbing all of them (avoiding CPU oversubscription); {@code -1}
     * opts out and leaves DuckDB's per-core default; a positive value is used verbatim. A single
     * batch ({@code threads <= 1}) always gets all cores. See
     * {@link com.gamma.util.DuckDbUtil#effectiveWorkerThreads}.
     *
     * <p>{@code largeFileBytes} drives the streaming plugin engine's per-batch mode pick: a batch
     * whose largest member is {@code >= largeFileBytes} runs in bounded <em>generation mode</em>
     * (huge single files), otherwise <em>union mode</em> (many small files packed → one transform/
     * write). {@code <= 0} forces union mode always. {@code flushRecords} is the per-generation row
     * budget used in generation mode.
     */
    @PublicApi(since = "2.0.0")
    public record Processing(int threads, int duckdbThreads, String filePattern,
                             int batchMaxFiles, long batchMaxBytes,
                             boolean duplicateCheckEnabled, String markerExtension,
                             int retentionDays, long largeFileBytes, long flushRecords) {}

    /**
     * Delimited-text parse settings. {@code engine} is {@code "auto"}/{@code "duckdb"}/
     * {@code "java"} — {@code auto} uses DuckDB's native reader for clean configs and the
     * Java parser otherwise (see {@link DuckDbCsvIngester#usesDuckDb}).
     *
     * <p>These settings may be authored inline under {@code processing.csv_settings} <em>or</em>
     * in a separate reusable grammar file referenced by {@code processing.grammar}; when both are
     * present the inline keys override the grammar file (see {@link #resolveGrammar}).
     *
     * <p>Fields added in 4.1 (all optional, defaults preserve prior behaviour):
     * {@code encoding}/{@code inputCompression}/{@code strictMode}/{@code nullStrings} pass through to
     * DuckDB {@code read_csv}; {@code includePrefixes}/{@code includeRegex}/{@code excludePrefixes}/
     * {@code excludeRegex} (anchored on {@code filterTargetColumn}, a 0-based selector index) compile
     * to a row-filter {@code WHERE} clause on the native path and an in-loop filter on the Java path.
     * {@code strictMode} is {@code null} when unset (⇒ DuckDB default).
     */
    @PublicApi(since = "2.0.0")
    public record CsvSettings(String delimiter, int skipHeaderLines, int skipJunkLines,
                              int skipTailLines, int skipTailCols, boolean hasHeader,
                              String engine, List<String> dateFormats, List<String> tsFormats,
                              String encoding, String inputCompression, Boolean strictMode,
                              List<String> nullStrings,
                              List<String> includePrefixes, List<String> includeRegex,
                              List<String> excludePrefixes, List<String> excludeRegex,
                              int filterTargetColumn) {

        /** Whether any of the row-filter lists is non-empty. */
        public boolean hasRowFilters() {
            return !includePrefixes.isEmpty() || !includeRegex.isEmpty()
                || !excludePrefixes.isEmpty() || !excludeRegex.isEmpty();
        }
    }

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

    /**
     * Fixed-width parsing frontend (additive, 4.1). Non-null only when the resolved grammar/
     * {@code csv_settings} sets {@code frontend: fixedwidth}; {@code null} for the default delimited
     * frontend (so every existing pipeline is unaffected).
     *
     * <p>Each record is carved into positional {@link Slice slices}. <b>Slice index {@code i} feeds the
     * schema field whose {@code selector} is {@code i}</b> — exactly the delimited path's
     * {@code c<selector>} column model — so the event {@code _schema.toon} (names/types/mapping/
     * partitions) is authored identically to a CSV source; only the tokenisation lives here.
     *
     * <ul>
     *   <li>{@code binary == false} (record: line) → DuckDB-native {@code read_csv}+{@code substring}
     *       ingest, reusing the whole CSV streaming/union/chunk path (see {@link DuckDbCsvIngester}).</li>
     *   <li>{@code binary == true} (record: bytes) → the {@code com.gamma.ingester.FixedWidthRecordIngester}
     *       plugin, wired via {@code processing.ingester} (it reads its layout from {@code ingester_config});
     *       this record is then unused.</li>
     * </ul>
     */
    @PublicApi(since = "4.1.0")
    public record FixedWidth(boolean binary, int recordLength, Trim trim,
                             int minRecordLength, List<Slice> slices) {
        /** One positional field: {@code start} (0-based), {@code length} (chars for text, bytes for binary), optional {@code name}. */
        public record Slice(String name, int start, int length) {}
        /** Field whitespace trimming applied at projection time (default {@link #BOTH}). */
        public enum Trim { NONE, LEFT, RIGHT, BOTH }
    }

    /**
     * Data-acquisition source binding (Data Acquisition roadmap Phase A; additive). <b>Never null</b> — a
     * pipeline with no {@code source:} block defaults to the local filesystem reading {@code dirs.poll} with
     * {@code includes = [processing.file_pattern]}, no excludes and unbounded depth: exactly the legacy scan.
     *
     * <p>{@code connector} selects the {@link com.gamma.acquire.SourceConnector} ({@code "local"} built-in;
     * other schemes via the optional connector module). {@code includes}/{@code excludes} are glob/regex
     * patterns (see {@link com.gamma.acquire.DiscoveryContext}); {@code recursiveDepth} of {@code -1} is
     * unbounded.
     */
    @PublicApi(since = "4.2.0")
    public record Source(String id, String connector, List<String> includes,
                         List<String> excludes, int recursiveDepth, Stability stability, String connection,
                         Duplicate duplicate) {
        public Source {
            includes = List.copyOf(includes);
            excludes = List.copyOf(excludes);
            if (stability == null) stability = Stability.DISABLED;
            if (duplicate == null) duplicate = Duplicate.PATH_DEFAULT;
        }

        /** A reusable connection-profile id this source binds to ({@code source.connection}), or {@code null}
         *  for the local filesystem. Resolved against the service's {@code *_connection.toon} registry. */
        public boolean hasConnection() { return connection != null && !connection.isBlank(); }
    }

    /**
     * Duplicate-detection + change policy for a source (Data Acquisition roadmap Phase C; additive,
     * {@code source.duplicate:}). {@code mode} selects how a re-seen path is judged — {@code path} (default =
     * today's {@code MarkerManager} sentinel), {@code metadata} (name+size+mtime), or {@code checksum}
     * ({@code algorithm} ∈ MD5/SHA256/CRC32, computed at processing time). {@code on_change} chooses what
     * happens when a known path's content changed: {@code ignore}/{@code reprocess}/{@code alert}/
     * {@code archive_old_version}. Parsed into {@link com.gamma.acquire.DuplicatePolicy} enums by the engine.
     *
     * <p>{@link #PATH_DEFAULT} (no {@code source.duplicate:} block) reproduces today's behaviour exactly.
     */
    @PublicApi(since = "4.2.0")
    public record Duplicate(String mode, String algorithm, String onChange) {
        /** Path-keyed dedup via marker sentinels — the legacy default. */
        public static final Duplicate PATH_DEFAULT = new Duplicate("path", "SHA256", "reprocess");
        public Duplicate {
            mode      = (mode == null || mode.isBlank()) ? "path" : mode.trim().toLowerCase();
            algorithm = (algorithm == null || algorithm.isBlank()) ? "SHA256" : algorithm.trim();
            onChange  = (onChange == null || onChange.isBlank()) ? "reprocess" : onChange.trim().toLowerCase();
        }
        /** Whether content-based dedup (a fingerprint ledger) is in effect (vs. the path-only default). */
        public boolean contentBased() { return !"path".equals(mode); }
    }

    /**
     * Readiness / stability detection for a source (Data Acquisition roadmap Phase B; additive,
     * {@code source.stability:}). When {@link #enabled} the engine holds a discovered file back until it has
     * stopped changing — {@link com.gamma.acquire.StabilityGate} releases it only once it has been quiescent
     * for {@link #windowMillis} and seen at the same size on {@link #sizeChecks} consecutive cycles — so a
     * half-written file is never ingested. A connector that knows readiness natively (or a {@link #readyMarker}
     * sentinel on the local connector) short-circuits this. {@code excludeTempFiles} merges
     * {@link #DEFAULT_TEMP_PATTERNS} (or {@code tempPatterns}) into the discovery excludes.
     *
     * <p>{@link #DISABLED} (no {@code source.stability:} block) is the legacy behaviour: a matched file is a
     * candidate immediately and nothing is stat'd for stability.
     */
    @PublicApi(since = "4.2.0")
    public record Stability(boolean enabled, long windowMillis, int sizeChecks,
                            String readyMarker, boolean excludeTempFiles, List<String> tempPatterns) {
        /** Temp / in-flight patterns excluded by default when stability gating is on (filename globs). */
        public static final List<String> DEFAULT_TEMP_PATTERNS =
                List.of("*.tmp", "*.partial", "*.filepart", ".~lock.*");
        /** No stability gating — the legacy "process a matched file at once" behaviour. */
        public static final Stability DISABLED =
                new Stability(false, 0L, 0, null, false, List.of());
        public Stability {
            tempPatterns = List.copyOf(tempPatterns);
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
    private final FixedWidth     fixedWidth;
    private final Source         source;

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
    /** Fixed-width frontend config, or {@code null} for the default delimited frontend. */
    public FixedWidth     fixedWidth() { return fixedWidth; }
    /** Data-acquisition source binding; never null (defaults to local-FS over {@code dirs.poll}). */
    public Source         source()     { return source; }

    // ── private constructor — use load() ──────────────────────────────────────

    private PipelineConfig(Builder b) {
        this.identity = new Identity(b.name, b.pipelineName, b.runTimestamp);
        this.dirs = new Dirs(b.pollDir, b.databaseDir, b.backupDir, b.tempDir, b.errorsDir,
                b.quarantineDir, b.markersDir, b.logDir, b.statusFilePath,
                b.batchesFilePath, b.lineageFilePath, b.manifestsDir, b.commitLogPath);
        this.processing = new Processing(b.threads, b.duckdbThreads, b.filePattern,
                b.batchMaxFiles, b.batchMaxBytes, b.duplicateCheckEnabled,
                b.markerExtension, b.retentionDays, b.largeFileBytes, b.flushRecords);
        this.csv = new CsvSettings(b.delimiter, b.skipHeaderLines, b.skipJunkLines,
                b.skipTailLines, b.skipTailCols, b.hasHeader, b.csvEngine,
                Collections.unmodifiableList(b.dateFormats),
                Collections.unmodifiableList(b.tsFormats),
                b.encoding, b.inputCompression, b.strictMode,
                Collections.unmodifiableList(b.nullStrings),
                Collections.unmodifiableList(b.includePrefixes),
                Collections.unmodifiableList(b.includeRegex),
                Collections.unmodifiableList(b.excludePrefixes),
                Collections.unmodifiableList(b.excludeRegex),
                b.filterTargetColumn);
        this.output = new Output(b.outputFormat, b.compression, b.duckLakeCfg);
        this.schemas = new Schemas(b.schemaSelector, b.singleSchema, b.segmentSchemas,
                b.ingesterClass,
                b.ingesterConfig != null
                        ? Collections.unmodifiableMap(b.ingesterConfig)
                        : Collections.emptyMap());
        this.duckdb   = new DuckDbSettings(b.duckMemoryLimit, b.duckTempDirectory, b.duckMaxTempSize);
        this.chunking = new Chunking(b.chunkMaxFileBytes, b.chunkTargetBytes);
        this.fixedWidth = b.fixedWidth;
        this.source = new Source(b.sourceId, b.sourceConnector, b.sourceIncludes,
                b.sourceExcludes, b.sourceDepth, b.sourceStability, b.sourceConnection, b.sourceDuplicate);
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

        // ── streaming plugin engine: mode threshold + generation budget (optional) ──
        Map<String, Object> streaming = (Map<String, Object>) proc.get("streaming");
        if (streaming != null) {
            Object lfb = streaming.get("large_file_bytes");
            if (lfb != null) b.largeFileBytes = Long.parseLong(String.valueOf(lfb));
            Object fr = streaming.get("flush_records");
            if (fr != null) b.flushRecords = Long.parseLong(String.valueOf(fr));
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

        // ── csv settings (inline csv_settings and/or external grammar file) ─────
        // The delimited parse grammar may live inline under processing.csv_settings, in a separate
        // reusable file referenced by processing.grammar, or both (inline keys win). resolveGrammar
        // returns the effective map (or null when neither is present — defaults then apply).
        Map<String, Object> csv = resolveGrammar(proc);
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
            // 4.1 additive: native read_csv pass-throughs + row filters
            b.encoding         = blankToNull(csv.get("encoding"));
            b.inputCompression = blankToNull(csv.get("compression"));
            b.strictMode       = parseBoolOrNull(csv.get("strict_mode"));
            b.nullStrings      = strList(csv.get("null_strings"));
            b.includePrefixes  = strList(csv.get("include_prefixes"));
            b.includeRegex     = strList(csv.get("include_regex"));
            b.excludePrefixes  = strList(csv.get("exclude_prefixes"));
            b.excludeRegex     = strList(csv.get("exclude_regex"));
            b.filterTargetColumn = toInt(csv.getOrDefault("filter_target_column", 0));
            // 4.1 additive: fixed-width frontend (null unless frontend: fixedwidth)
            b.fixedWidth       = parseFixedWidth(csv);
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
                validateFixedWidthSelectors(b.fixedWidth, schemaCfg, "schemas[col=" + colCount + "]");

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
            validateFixedWidthSelectors(b.fixedWidth, b.singleSchema, "schema_file");
        }

        // ── source / connector (additive; absent ⇒ implicit LOCAL reading dirs.poll) ──────────────
        // A pipeline with no `source:` block scans the local poll dir exactly as before: the single
        // processing.file_pattern glob, no excludes, unbounded depth. A `source:` block selects a
        // connector and overrides discovery (include/exclude/recursive_depth).
        b.sourceId       = b.pipelineName;
        b.sourceIncludes = new ArrayList<>(List.of(b.filePattern));
        Map<String, Object> src = (Map<String, Object>) raw.get("source");
        if (src != null) {
            b.sourceId        = opt(src, "id", b.pipelineName);
            b.sourceConnector = opt(src, "connector", "local").toLowerCase();
            List<String> inc  = strList(src.get("include"));
            if (!inc.isEmpty()) b.sourceIncludes = inc;
            b.sourceExcludes  = strList(src.get("exclude"));
            Object depth = src.get("recursive_depth");
            if (depth != null) b.sourceDepth = toInt(depth);
            // Reusable connection-profile binding (resolved against the service's *_connection.toon registry;
            // remote-connector construction from it is roadmap Phase E — the id is parsed/stored now).
            b.sourceConnection = opt(src, "connection", null);

            // ── duplicate-detection / change policy (Phase C; additive, absent ⇒ PATH = today) ─────────
            Map<String, Object> dupBlock = (Map<String, Object>) src.get("duplicate");
            if (dupBlock != null) {
                b.sourceDuplicate = new Duplicate(
                        opt(dupBlock, "mode", "path"),
                        opt(dupBlock, "algorithm", "SHA256"),
                        opt(dupBlock, "on_change", "reprocess"));
            }

            // ── readiness / stability (Phase B; additive sub-block, absent ⇒ DISABLED) ──────────
            Map<String, Object> stab = (Map<String, Object>) src.get("stability");
            if (stab != null) {
                long windowMs = toMillis(opt(stab, "window", "30s"));
                int  checks   = Math.max(1, toInt(stab.getOrDefault("size_checks", 2)));
                String marker = opt(stab, "ready_marker", null);
                boolean excludeTmp = !"false".equalsIgnoreCase(
                        String.valueOf(stab.getOrDefault("exclude_temp_files", "true")));
                List<String> tmp = strList(stab.get("exclude_temp_patterns"));
                b.sourceStability = new Stability(true, windowMs, checks,
                        (marker == null || marker.isBlank()) ? null : marker.trim(),
                        excludeTmp,
                        tmp.isEmpty() ? Stability.DEFAULT_TEMP_PATTERNS : tmp);
            }
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

    /**
     * Parse a duration string to milliseconds, using the same {@code s/m/h/d} suffix grammar as
     * {@code AlertRule.window} (e.g. {@code "30s"}, {@code "5m"}, {@code "2h"}, {@code "1d"}); a bare number is
     * read as seconds. {@code null}/blank ⇒ 0.
     */
    private static long toMillis(String d) {
        if (d == null || d.isBlank()) return 0L;
        d = d.trim();
        char last = d.charAt(d.length() - 1);
        if (Character.isDigit(last)) return Long.parseLong(d) * 1000L;   // bare number ⇒ seconds
        long n = Long.parseLong(d.substring(0, d.length() - 1).trim());
        return switch (Character.toLowerCase(last)) {
            case 's' -> n * 1_000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            default  -> throw new IllegalArgumentException("not a duration (expected Ns/Nm/Nh/Nd): " + d);
        };
    }

    /** Trim a config value to a String; {@code null}/blank ⇒ {@code null}. */
    private static String blankToNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** Parse a tri-state boolean: {@code null}/blank ⇒ {@code null} (unset), else parsed. */
    private static Boolean parseBoolOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Boolean.valueOf(Boolean.parseBoolean(s));
    }

    /**
     * Coerce a config value to a list of trimmed, non-empty strings. Accepts a JToon array
     * ({@code List}) or a comma-separated scalar; {@code null} ⇒ empty list.
     */
    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v) {
        if (v == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        Iterable<?> items = (v instanceof List<?> l) ? l : Arrays.asList(String.valueOf(v).split(","));
        for (Object it : items) {
            String s = String.valueOf(it).trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * Parse the optional fixed-width frontend from the resolved grammar/{@code csv_settings} map.
     * Returns {@code null} unless {@code frontend} is {@code fixedwidth}; otherwise builds an
     * immutable {@link FixedWidth} from the {@code fixedwidth} block. Hard-fails (so a draft is
     * rejected before any run) on a missing block, an empty/ill-formed {@code fields[]}, a
     * negative {@code start}/non-positive {@code length}, or {@code record: bytes} without a
     * positive {@code record_length}. {@code min_record_length} defaults to the widest slice end.
     */
    @SuppressWarnings("unchecked")
    private static FixedWidth parseFixedWidth(Map<String, Object> csv) {
        String frontend = String.valueOf(csv.getOrDefault("frontend", "delimited")).trim().toLowerCase();
        if (!frontend.equals("fixedwidth") && !frontend.equals("fixed_width")) return null;

        Object fwRaw = csv.get("fixedwidth");
        if (!(fwRaw instanceof Map<?, ?> fwMap))
            throw new IllegalArgumentException(
                    "frontend 'fixedwidth' requires a 'fixedwidth:' block with fields[]{name,start,length}");
        Map<String, Object> fw = (Map<String, Object>) fwMap;

        boolean binary = "bytes".equalsIgnoreCase(String.valueOf(fw.getOrDefault("record", "line")).trim());
        int recordLength = toInt(fw.getOrDefault("record_length", 0));
        FixedWidth.Trim trim = parseTrim(fw.get("trim"));

        if (!(fw.get("fields") instanceof List<?> list) || list.isEmpty())
            throw new IllegalArgumentException("fixedwidth.fields[] must be a non-empty list of {name,start,length}");

        List<FixedWidth.Slice> slices = new ArrayList<>();
        int maxEnd = 0;
        for (Object o : list) {
            Map<String, Object> f = (Map<String, Object>) o;
            int start  = toInt(f.getOrDefault("start", -1));
            int length = toInt(f.getOrDefault("length", 0));
            if (start < 0)
                throw new IllegalArgumentException(
                        "fixedwidth.fields[" + slices.size() + "].start must be >= 0 (got " + start + ")");
            if (length < 1)
                throw new IllegalArgumentException(
                        "fixedwidth.fields[" + slices.size() + "].length must be >= 1 (got " + length + ")");
            String name = f.get("name") == null ? null : String.valueOf(f.get("name"));
            slices.add(new FixedWidth.Slice(name, start, length));
            maxEnd = Math.max(maxEnd, start + length);
        }
        if (binary && recordLength <= 0)
            throw new IllegalArgumentException("fixedwidth.record_length must be > 0 when record: bytes");

        int minLen = toInt(fw.getOrDefault("min_record_length", 0));
        if (minLen <= 0) minLen = maxEnd;   // default: keep any line that reaches the widest slice
        return new FixedWidth(binary, recordLength, trim, minLen, Collections.unmodifiableList(slices));
    }

    /** Parse the {@code trim} mode; accepts the enum names or {@code true}/{@code false}; default {@code BOTH}. */
    private static FixedWidth.Trim parseTrim(Object v) {
        if (v == null) return FixedWidth.Trim.BOTH;
        return switch (String.valueOf(v).trim().toLowerCase()) {
            case "none", "false" -> FixedWidth.Trim.NONE;
            case "left", "ltrim" -> FixedWidth.Trim.LEFT;
            case "right", "rtrim" -> FixedWidth.Trim.RIGHT;
            default -> FixedWidth.Trim.BOTH;   // "both" / "true" / anything else
        };
    }

    /**
     * For a fixed-width <em>text</em> frontend, every schema {@code raw.fields[].selector} indexes a
     * declared slice — fail the load (clear message) if a selector has no matching slice. No-op for the
     * delimited frontend or the binary frontend (which loads its own layout from {@code ingester_config}).
     */
    private static void validateFixedWidthSelectors(FixedWidth fw, Map<String, Object> schema, String label) {
        if (fw == null || fw.binary()) return;
        ParserSpec ps = ParserSpec.fromSchema(schema);
        if (ps.maxSelector() >= fw.slices().size())
            throw new IllegalArgumentException(label + ": raw.fields selector " + ps.maxSelector()
                    + " has no matching fixedwidth slice (only " + fw.slices().size() + " slice(s) defined)");
    }

    /**
     * Resolve the effective delimited-parse settings map from {@code processing}: load the external
     * grammar file at {@code processing.grammar} (if any), then overlay the inline
     * {@code processing.csv_settings} (so inline keys win for local overrides). Returns {@code null}
     * when neither is present (defaults then apply, preserving pre-4.1 behaviour).
     *
     * @throws FileNotFoundException if {@code processing.grammar} names a file that does not exist
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveGrammar(Map<String, Object> proc) throws IOException {
        String grammarPath = blankToNull(proc.get("grammar"));
        Map<String, Object> inline = (Map<String, Object>) proc.get("csv_settings");
        Map<String, Object> grammar = null;
        if (grammarPath != null) {
            Path gp = Paths.get(grammarPath);
            if (!Files.exists(gp))
                throw new FileNotFoundException("Grammar file not found: " + grammarPath);
            grammar = (Map<String, Object>)
                    JToon.decode(Files.readString(gp, StandardCharsets.UTF_8));
            log.info("[CONFIG] Delimited grammar: {}", grammarPath);
        }
        if (grammar == null && inline == null) return null;
        Map<String, Object> merged = new LinkedHashMap<>();
        if (grammar != null) merged.putAll(grammar);
        if (inline  != null) merged.putAll(inline);   // inline overrides grammar
        return merged;
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
        long   largeFileBytes  = 268_435_456L;   // 256 MB: streaming plugin generation-mode threshold
        long   flushRecords    = 5_000_000L;      // streaming plugin generation row budget
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
        String       encoding;
        String       inputCompression;
        Boolean      strictMode;
        List<String> nullStrings     = new ArrayList<>();
        List<String> includePrefixes = new ArrayList<>();
        List<String> includeRegex    = new ArrayList<>();
        List<String> excludePrefixes = new ArrayList<>();
        List<String> excludeRegex    = new ArrayList<>();
        int          filterTargetColumn = 0;
        FixedWidth   fixedWidth;          // null ⇒ delimited frontend (the default)
        String       sourceId;
        String       sourceConnector = "local";
        List<String> sourceIncludes  = new ArrayList<>();
        List<String> sourceExcludes  = new ArrayList<>();
        int          sourceDepth     = -1;
        Stability    sourceStability = Stability.DISABLED;
        String       sourceConnection;   // null ⇒ no connection-profile binding (local)
        Duplicate    sourceDuplicate = Duplicate.PATH_DEFAULT;
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
