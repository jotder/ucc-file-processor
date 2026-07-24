package com.gamma.etl;

import com.gamma.api.PublicApi;
import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * Schema resolution — at most one of {@code selector} (multi-schema {@code schemas[]}),
     * {@code single} (legacy {@code schema_file}), or {@code segments} (plugin path) is
     * non-null; all three are {@code null} for a schema-less <em>draft</em> (v5.1.0 — allowed
     * only while {@code active: false}; arming without a schema is rejected at parse).
     * {@code ingesterClass} is the plugin FQCN ({@code null} for built-in CSV);
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
     * JSON / NDJSON parsing frontend (additive, 4.8). Non-null only when the resolved parsing
     * settings set {@code frontend: json}; {@code null} otherwise (so every existing pipeline is
     * unaffected).
     *
     * <p>The frontend compiles to DuckDB {@code read_ndjson} ({@code format: newline}, the default)
     * or {@code read_json} ({@code format: array | auto}). Each schema field lands as a VARCHAR
     * column keyed by {@code raw.fields[].selector} — for this frontend the selector is the
     * <b>top-level JSON key</b>, not a column index — so the typing/mapping/partition/lineage
     * backend runs unchanged. Nested values: select the wrapping key and carve with an {@code EXPR}
     * mapping rule ({@code json_extract_string(...)}).
     *
     * @param format      {@code newline} (NDJSON, one object per line) | {@code array} | {@code auto}
     * @param recordsPath JSONPath to the record array; only {@code "$"} (the default) is supported
     */
    @PublicApi(since = "4.8.0")
    public record Json(String format, String recordsPath) {
        /** Whether the input is newline-delimited (one JSON object per physical line). */
        public boolean newlineDelimited() { return "newline".equals(format); }
    }

    /**
     * Text/regex parsing frontend (additive, 4.8; block records additive, 4.9). Non-null only when
     * the resolved parsing settings set {@code frontend: text_regex}; {@code null} otherwise.
     *
     * <p>Default ({@code recordSplit == "\n"}): each physical line is read intact as a single
     * VARCHAR column (the fixed-width {@code read_csv} single-column trick), lines matching
     * {@code pattern} are kept, and each named capture group becomes a VARCHAR column.
     *
     * <p>Block mode ({@code recordSplit} any other delimiter, e.g. {@code "\n\n"} for
     * {@code blank_line}): the whole file is read as text and split into records on the literal
     * delimiter, so a record may span multiple physical lines; {@code pattern} is then matched
     * against each record's full (trimmed) text with {@code .} matching newlines, letting a single
     * capture group span what were previously separate lines.
     *
     * <p>Either way, a schema field's {@code raw.fields[].selector} names the capture group that
     * feeds it, so the typing/mapping/partition/lineage backend runs unchanged. Non-matching
     * lines/records are dropped (like fixed-width short lines).
     *
     * @param recordSplit record separator; {@code "\n"} (one record per line) is the default;
     *                    {@code "blank_line"} is normalised to {@code "\n\n"}; any other literal
     *                    string is used as-is as the block delimiter
     * @param pattern     the RE2 regex with at least one named capture group, normalised to the
     *                    {@code (?P<name>...)} spelling DuckDB accepts
     * @param groupNames  the named capture groups in declaration order (⇒ DuckDB name_list order)
     */
    @PublicApi(since = "4.8.0")
    public record TextRegex(String recordSplit, String pattern, List<String> groupNames) {
        public TextRegex {
            groupNames = List.copyOf(groupNames);
        }
    }

    /**
     * Data-acquisition source binding (Data Acquisition roadmap Phase A; additive). <b>Never null</b> — a
     * pipeline with no {@code source:} block defaults to the local filesystem reading {@code dirs.poll} with
     * {@code includes = [processing.file_pattern]}, no excludes and unbounded depth: exactly the legacy scan.
     *
     * <p>{@code connector} selects the {@link com.gamma.acquire.CollectorConnector} ({@code "local"} built-in;
     * other schemes via the optional connector module). {@code includes}/{@code excludes} are glob/regex
     * patterns (see {@link com.gamma.acquire.DiscoveryContext}); {@code recursiveDepth} of {@code -1} is
     * unbounded.
     */
    @PublicApi(since = "4.2.0")
    public record Collector(String id, String connector, List<String> includes,
                         List<String> excludes, int recursiveDepth, Stability stability, String connection,
                         Duplicate duplicate, Guarantee guarantee, GapDetection gapDetection,
                         Fetch fetch, Retry retry, CircuitBreaker circuitBreaker, PostActionConfig postAction,
                         Incremental incremental, String discovery) {
        public Collector {
            includes = List.copyOf(includes);
            excludes = List.copyOf(excludes);
            // ACQ-6: how new files are noticed — interval "poll" (default) or filesystem-event "watch"
            // (local sources only; the poll loop stays on as the backstop either way).
            discovery = (discovery == null || discovery.isBlank()) ? "poll" : discovery.trim().toLowerCase();
            if (stability == null) stability = Stability.DISABLED;
            if (duplicate == null) duplicate = Duplicate.PATH_DEFAULT;
            if (guarantee == null) guarantee = Guarantee.BEST_EFFORT;
            if (gapDetection == null) gapDetection = GapDetection.DISABLED;
            if (fetch == null) fetch = Fetch.DEFAULT;
            if (retry == null) retry = Retry.DISABLED;
            if (circuitBreaker == null) circuitBreaker = CircuitBreaker.DISABLED;
            if (postAction == null) postAction = PostActionConfig.RETAIN;
            if (incremental == null) incremental = Incremental.DISABLED;
        }

        /** A reusable connection-profile id this source binds to ({@code source.connection}), or {@code null}
         *  for the local filesystem. Resolved against the service's {@code *_connection.toon} registry. */
        public boolean hasConnection() { return connection != null && !connection.isBlank(); }
    }

    /**
     * Collection-guarantee level for a source (Data Acquisition roadmap Phase D; additive, {@code source.guarantee:}).
     * The teeth live in machinery that already exists: the fsync'd {@link CommitLog} gives idempotent replay
     * after a crash, and the Phase-C fingerprint ledger ({@code source.duplicate.mode != path}) skips an
     * already-processed file. So this knob is <b>declarative</b> — {@link #AT_LEAST_ONCE}/{@link #EXACTLY_ONCE}
     * {@linkplain #requiresLedger() require a ledger} to hold; the engine logs a warning if a stronger guarantee
     * is declared over path-only (marker) dedup, and behaves as best-effort + commit-log replay in that case.
     */
    @PublicApi(since = "4.2.0")
    public enum Guarantee {
        /** Today's behaviour: markers + commit log, no fingerprint ledger required. */ BEST_EFFORT,
        /** Every file is processed at least once (ledger-backed; safe to re-fetch). */  AT_LEAST_ONCE,
        /** Logical exactly-once: the ledger marks processed only after the batch commits. */ EXACTLY_ONCE;

        public static Guarantee from(String s) {
            if (s == null || s.isBlank()) return BEST_EFFORT;
            return switch (s.trim().toUpperCase()) {
                case "AT_LEAST_ONCE", "AT-LEAST-ONCE" -> AT_LEAST_ONCE;
                case "EXACTLY_ONCE", "EXACTLY-ONCE"   -> EXACTLY_ONCE;
                default -> BEST_EFFORT;
            };
        }
        /** Whether this guarantee needs the fingerprint ledger (content-based dedup) to actually hold. */
        public boolean requiresLedger() { return this != BEST_EFFORT; }
    }

    /**
     * Sequence-gap detection for a source (Data Acquisition roadmap Phase D; additive, {@code source.gap_detection:}).
     * When {@link #active()} the engine, after discovery, checks the observed file names against the
     * {@link #sequence} strftime-style template (e.g. {@code "CDR_{yyyyMMddHH}"}) and emits an
     * {@link com.gamma.event.EventType#SEQUENCE_GAP} event per missing key — so "no file silently missed" is a
     * recorded, queryable operational fact. See {@link com.gamma.acquire.GapDetector}.
     *
     * <p>{@link #DISABLED} (no {@code source.gap_detection:} block) ⇒ no series check (the legacy behaviour).
     */
    @PublicApi(since = "4.2.0")
    public record GapDetection(boolean enabled, String sequence) {
        /** No gap detection — the legacy behaviour. */
        public static final GapDetection DISABLED = new GapDetection(false, null);
        /** Whether gap detection should run (enabled and given a non-blank sequence template). */
        public boolean active() { return enabled && sequence != null && !sequence.isBlank(); }
    }

    /**
     * Duplicate-detection + change policy for a source (Data Acquisition roadmap Phase C; additive,
     * {@code source.duplicate:}). {@code mode} selects how a re-seen path is judged — {@code path} (default =
     * today's {@code MarkerManager} sentinel), {@code metadata} (name+size+mtime), {@code checksum}
     * ({@code algorithm} ∈ MD5/SHA256/CRC32, computed at processing time), or {@code etag} (ACQ-7: the
     * connector-supplied listing etag/object version, falling back to metadata when the connector has neither
     * — pre-fetch-capable, so an unchanged remote object is skipped without a download). {@code on_change}
     * chooses what
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
     * Incremental discovery / high-watermark for a source (Data Acquisition roadmap Phase C4; additive,
     * {@code source.incremental:}). When {@link #enabled()} the engine drops any discovered candidate whose
     * modification time is <em>strictly older</em> than the source's <b>high-watermark</b> — the greatest
     * {@code last_modified} of any file the {@linkplain com.gamma.acquire.AcquisitionLedger fingerprint ledger}
     * has already recorded for this source — so a re-scan only re-examines the recent frontier instead of
     * re-LIST'ing/re-fetching (remote) or re-stat'ing the deep history (local).
     *
     * <p>The watermark is <em>derived</em> from the ledger (max recorded {@code last_modified}), so this knob
     * only has effect alongside a content-based {@code source.duplicate} mode (metadata/checksum) — with the
     * path-only default the ledger is empty and the filter no-ops. It is an optimisation for monotonic-arrival
     * sources (timestamps that only increase, e.g. {@code CDR_<ts>} feeds); a file re-uploaded <em>below</em>
     * the watermark is intentionally skipped, so leave it off if you must catch arbitrarily back-dated
     * re-uploads. The frontier ({@code == watermark}) is never blindly skipped — it passes through to the
     * ledger for exact dedup.
     *
     * <p>{@link #DISABLED} (no {@code source.incremental:} block) ⇒ the full discovery listing (legacy behaviour).
     */
    @PublicApi(since = "4.2.0")
    public record Incremental(String watermark) {
        /** The high-watermark dimension; {@code last_modified} is the only one implemented (etag/version future). */
        public static final String LAST_MODIFIED = "last_modified";
        /** No incremental filtering — the legacy full-listing behaviour. */
        public static final Incremental DISABLED = new Incremental(null);
        public Incremental {
            watermark = (watermark == null || watermark.isBlank()) ? null : watermark.trim().toLowerCase();
        }
        /** Whether incremental high-watermark filtering should run. */
        public boolean enabled() { return LAST_MODIFIED.equals(watermark); }
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

    /**
     * Retrieval tuning for a remote source (Data Acquisition roadmap Phase E/F; additive, {@code source.fetch:}).
     * {@code parallelFetch} > 1 fetches ready files concurrently over a pool of independent connector sessions
     * (each connector instance holds one non-thread-safe session, so concurrency = a pool, not shared reuse);
     * {@code rateLimitBytesPerSec} > 0 throttles aggregate transfer via a token bucket. {@code mode} is advisory
     * (the file-based batch path needs a local copy, so a remote source always stages).
     *
     * <p>{@link #DEFAULT} (no block) ⇒ sequential, unthrottled — exactly the Phase-E behaviour.
     */
    @PublicApi(since = "4.2.0")
    public record Fetch(String mode, String stagingDir, int parallelFetch, long rateLimitBytesPerSec) {
        public static final Fetch DEFAULT = new Fetch("STAGE", null, 1, 0L);
        public Fetch {
            mode = (mode == null || mode.isBlank()) ? "STAGE" : mode.trim().toUpperCase();
            if (parallelFetch < 1) parallelFetch = 1;
            if (rateLimitBytesPerSec < 0) rateLimitBytesPerSec = 0L;
        }
        /** Whether more than one file should be fetched at a time (needs a connector-session pool). */
        public boolean parallel() { return parallelFetch > 1; }
        /** Whether aggregate transfer is rate-limited. */
        public boolean rateLimited() { return rateLimitBytesPerSec > 0; }
    }

    /**
     * Retry/backoff policy for transient acquisition faults (Data Acquisition roadmap Phase F; additive,
     * {@code source.retry:}). Wraps connectivity-sensitive operations (discover, per-file fetch); {@code count}
     * is the number of <em>retries</em> after the first attempt, {@code backoff} ∈ {@code EXPONENTIAL|LINEAR|FIXED}
     * with full jitter, bounded by {@code initialDelay}…{@code maxDelay}. Realised by
     * {@link com.gamma.acquire.retry.RetryPolicy}.
     *
     * <p>{@link #DISABLED} (no block, {@code count == 0}) ⇒ a single attempt — exactly today's behaviour.
     */
    @PublicApi(since = "4.2.0")
    public record Retry(int count, String backoff, long initialDelayMillis, long maxDelayMillis) {
        public static final Retry DISABLED = new Retry(0, "EXPONENTIAL", 1_000L, 60_000L);
        public Retry {
            if (count < 0) count = 0;
            backoff = (backoff == null || backoff.isBlank()) ? "EXPONENTIAL" : backoff.trim().toUpperCase();
            if (initialDelayMillis <= 0) initialDelayMillis = 1_000L;
            if (maxDelayMillis < initialDelayMillis) maxDelayMillis = initialDelayMillis;
        }
        /** Whether any retry is configured (vs. a single attempt). */
        public boolean enabled() { return count > 0; }
    }

    /**
     * Per-source circuit breaker (Data Acquisition roadmap Phase F; additive, {@code source.circuit_breaker:}).
     * After {@code failureThreshold} consecutive connectivity failures the source is tripped OPEN and skipped for
     * {@code cooldownMillis} (then a single HALF_OPEN trial) instead of hammering a dead endpoint. Realised by the
     * process-wide {@link com.gamma.acquire.CircuitBreaker#shared()}.
     *
     * <p>{@link #DISABLED} (no block) ⇒ never trips — exactly today's behaviour.
     */
    @PublicApi(since = "4.2.0")
    public record CircuitBreaker(boolean enabled, int failureThreshold, long cooldownMillis) {
        public static final CircuitBreaker DISABLED = new CircuitBreaker(false, 5, 300_000L);
        public CircuitBreaker {
            if (failureThreshold < 1) failureThreshold = 1;
            if (cooldownMillis < 0) cooldownMillis = 0L;
        }
    }

    /**
     * Source-side post-processing action applied after a fetched file is integrity-validated and staged
     * (Data Acquisition roadmap Phase F; additive, {@code source.post_action:}). {@code onSuccess} ∈
     * {@code RETAIN|DELETE|MOVE|RENAME|TAG} (validated against the connector's
     * {@link com.gamma.acquire.CollectorConnector.Capability capabilities}); {@code onUnsupported} ∈
     * {@code FAIL|WARN_AND_CONTINUE|IGNORE} decides what happens when the connector can't perform it.
     * {@code archivePath} (a {@code yyyy/MM/dd}-style template) is used by {@code MOVE}; {@code tags} by {@code TAG}.
     *
     * <p>{@link #RETAIN} (no block) leaves the source untouched — exactly today's behaviour.
     */
    @PublicApi(since = "4.2.0")
    public record PostActionConfig(String onSuccess, String archivePath, Map<String, String> tags,
                                   String onUnsupported) {
        public static final PostActionConfig RETAIN =
                new PostActionConfig("RETAIN", null, Map.of(), "WARN_AND_CONTINUE");
        public PostActionConfig {
            onSuccess     = (onSuccess == null || onSuccess.isBlank()) ? "RETAIN" : onSuccess.trim().toUpperCase();
            onUnsupported = (onUnsupported == null || onUnsupported.isBlank())
                    ? "WARN_AND_CONTINUE" : onUnsupported.trim().toUpperCase();
            tags = (tags == null) ? Map.of() : Map.copyOf(tags);
        }
        /** Whether a non-RETAIN finalization should run on the source-side file after success. */
        public boolean active() { return !"RETAIN".equals(onSuccess); }
    }

    /**
     * What this pipeline's output registers as in the Catalog ({@code produces:} top-level key,
     * v5.1.0; absent ⇒ {@link #STREAM} — exactly the prior behaviour). A {@code reference}
     * pipeline's partitioned output is a <b>Reference Dataset</b> (dimension/lookup data origin)
     * rather than an event/fact Stream: the catalog registers it standalone (id
     * {@code ref:<pipeline>}) and Stage-2 enrichments may bind it by name
     * ({@code references.<name>.ref:}) instead of a raw path.
     */
    @PublicApi(since = "5.1.0")
    public enum Produces {
        /** Event/fact data origin — the default; the catalog registers a Stream. */
        STREAM,
        /** Dimension/lookup data origin — the catalog registers a standalone Reference Dataset. */
        REFERENCE;

        /** Parse the {@code produces:} value; blank/absent ⇒ {@link #STREAM}, anything else must match. */
        public static Produces from(String s) {
            if (s == null || s.isBlank()) return STREAM;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "STREAM"    -> STREAM;
                case "REFERENCE" -> REFERENCE;
                default -> throw new IllegalArgumentException(
                        "produces must be 'stream' or 'reference', got: '" + s + "'");
            };
        }
    }

    /**
     * How a {@code produces: reference} pipeline's Reference Dataset is loaded ({@code reference.load:},
     * Reference Phase-2; absent ⇒ {@link #REPLACE} = exactly today's behaviour). {@link #REPLACE}
     * rewrites the whole partition each run (v1 full-replace semantics); {@link #UPSERT} keeps the
     * latest version per declared {@code reference.key} (latest-version-wins); {@link #SCD2}
     * additionally preserves superseded versions as slowly-changing-dimension history. {@code UPSERT}
     * and {@code SCD2} require a non-empty {@code reference.key}. The engine mechanics land in later
     * phases (P1/P2); P0 only carries and validates the config.
     */
    @PublicApi(since = "5.2.0")
    public enum Load {
        /** Full-replace — the default; rewrites the partition each run (v1 semantics). */
        REPLACE,
        /** Latest-version-wins per {@code reference.key} (needs a key). */
        UPSERT,
        /** SCD-2 history: keeps superseded versions as well as the current one (needs a key). */
        SCD2;

        /** Parse the {@code reference.load:} value; blank/absent ⇒ {@link #REPLACE}, else must match. */
        public static Load from(String s) {
            if (s == null || s.isBlank()) return REPLACE;
            return switch (s.trim().toUpperCase(Locale.ROOT)) {
                case "REPLACE" -> REPLACE;
                case "UPSERT"  -> UPSERT;
                case "SCD2"    -> SCD2;
                default -> throw new IllegalArgumentException(
                        "reference.load must be 'replace', 'upsert' or 'scd2', got: '" + s + "'");
            };
        }

        /** Whether this load mode needs a declared {@code reference.key} (upsert/scd2 do). */
        public boolean requiresKey() { return this != REPLACE; }
    }

    /**
     * The optional {@code reference:} block on a {@code produces: reference} pipeline (Reference
     * Phase-2; additive). Declares the load semantics of the produced Reference Dataset. <b>Never
     * null</b> — absent ⇒ {@link #DEFAULT} (full-replace, no key, no refresh timer), i.e. today's
     * behaviour, so every existing pipeline parses and runs identically. The block is only meaningful
     * when {@code produces: reference}; on a Stream pipeline it is inert.
     *
     * @param key            declared identity columns (empty unless upsert/scd2); each must exist in
     *                       the pipeline schema (validated at parse when a schema is resolved)
     * @param load           {@link Load#REPLACE} (default) | {@link Load#UPSERT} | {@link Load#SCD2}
     * @param refreshSeconds {@code 0} = re-materialize on collect only (today); {@code >0} arms a
     *                       periodic compaction/re-materialize timer (Phase-3 — parsed/stored now)
     */
    @PublicApi(since = "5.2.0")
    public record Reference(List<String> key, Load load, int refreshSeconds) {
        /** Full-replace, no key, no refresh timer — exactly the pre-Phase-2 behaviour. */
        public static final Reference DEFAULT = new Reference(List.of(), Load.REPLACE, 0);
        public Reference {
            key = (key == null) ? List.of() : List.copyOf(key);
            if (load == null) load = Load.REPLACE;
            if (refreshSeconds < 0) refreshSeconds = 0;
        }
        /** Whether a periodic refresh/compaction timer should be armed (Phase-3). */
        public boolean refreshEnabled() { return refreshSeconds > 0; }
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
    private final Json           json;
    private final TextRegex      textRegex;
    private final Collector      collector;

    /**
     * Whether this pipeline is activated for execution ({@code active:} top-level key, v4.7.0). Only
     * activated pipelines are run by the poll cycle / multi-source orchestrator; an inactive pipeline
     * is still parsed, indexed and queryable (it shows in {@code /runs}) but never executed.
     *
     * <p><b>Default is {@code false}</b> — a pipeline must opt in with {@code active: true}. This is a
     * deliberate fail-safe so a freshly-dropped or half-edited config never runs until explicitly armed.
     */
    private final boolean active;

    /** What the output registers as in the Catalog ({@code produces:}, v5.1.0; default STREAM). */
    private final Produces produces;

    /**
     * The {@code reference:} block load semantics (Reference Phase-2; never null, {@link Reference#DEFAULT}
     * when absent). Only meaningful for a {@code produces: reference} pipeline; inert otherwise.
     */
    private final Reference reference;

    /**
     * The logical Catalog <b>Stream</b> this pipeline is a member of ({@code stream:}, Reference
     * Phase-2 / GLOSSARY §3; never null). Defaults to the pipeline's own name, preserving today's
     * strict 1:1 pipeline↔Stream mapping; several pipelines sharing one {@code stream:} name are
     * grouped under a single Stream in the catalog graph (P4). Normalised like the pipeline name
     * (lowercased, spaces→underscores) and validated as a SQL identifier.
     */
    private final String stream;

    /**
     * The optional entry-node {@code trigger:} block (T13 / §3.6) verbatim, or {@code null} when absent.
     * Absent ⇒ the pipeline rides the default poll cycle exactly as before; present ⇒ the live loop
     * ({@link com.gamma.service.CollectorService}) classifies it via {@code com.gamma.pipeline.PipelineTrigger}
     * into {@code schedule}(every/cron) / {@code event} / {@code manual}. Carried onto the lifted
     * acquisition node so the flow projection and the live driver agree on the schedule.
     */
    private final Map<String, Object> trigger;

    /**
     * Other config files this pipeline read at parse time (schema / grammar / segment {@code .toon}s),
     * as given in the file (not absolutised). Used by {@link com.gamma.service.ConfigRegistry} to detect
     * on-disk changes (mtime) and reload only when something actually changed. The pipeline file itself
     * is tracked separately by the registry (it owns that path). Never null.
     */
    private final List<Path> referencedFiles;

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
    /** JSON/NDJSON frontend config, or {@code null} unless {@code frontend: json}. */
    public Json           json()       { return json; }
    /** Text/regex frontend config, or {@code null} unless {@code frontend: text_regex}. */
    public TextRegex      textRegex()  { return textRegex; }
    /** Data-acquisition source binding; never null (defaults to local-FS over {@code dirs.poll}). */
    public Collector      collector()  { return collector; }
    /** Whether this pipeline is activated for execution ({@code active:}, default {@code false}). */
    public boolean        active()     { return active; }
    /** What the output registers as in the Catalog ({@code produces:}, default {@link Produces#STREAM}). */
    public Produces       produces()   { return produces; }
    /** Whether this pipeline's output is a Reference Dataset ({@code produces: reference}). */
    public boolean producesReference() { return produces == Produces.REFERENCE; }
    /** The {@code reference:} load semantics; never null ({@link Reference#DEFAULT} when absent). */
    public Reference       reference()  { return reference; }
    /** The logical Catalog Stream this pipeline belongs to ({@code stream:}, default = pipeline name). */
    public String          stream()     { return stream; }
    /** The raw entry-node {@code trigger:} block (T13), or {@code null} when absent (⇒ default poll). */
    public Map<String, Object> triggerConfig() { return trigger; }
    /** The schema/grammar/segment files this config referenced at parse time (for change-watching). */
    public List<Path>     referencedFiles() { return referencedFiles; }

    // ── constructor — package-private; populated by PipelineConfigParser, use load() ──

    PipelineConfig(Builder b) {
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
        this.json = b.json;
        this.textRegex = b.textRegex;
        this.collector = new Collector(b.sourceId, b.collectorConnector, b.sourceIncludes,
                b.sourceExcludes, b.sourceDepth, b.sourceStability, b.sourceConnection, b.sourceDuplicate,
                b.sourceGuarantee, b.sourceGapDetection,
                b.sourceFetch, b.sourceRetry, b.sourceCircuitBreaker, b.sourcePostAction, b.sourceIncremental,
                b.sourceDiscovery);
        this.statusDirToPrepare = b.statusDirToPrepare;
        this.active = b.active;
        this.produces = b.produces;
        this.reference = b.reference;
        this.stream = b.stream;
        this.trigger = b.trigger;
        this.referencedFiles = List.copyOf(b.referencedFiles);
    }

    /**
     * Copy constructor used by {@link #forNewRun()} — clones every parsed group verbatim but stamps a
     * fresh {@code runTimestamp} and recomputes the run-timestamped status/batch/lineage/manifest paths
     * (the persistent commit log is left as-is). Performs <b>no disk I/O</b>: schemas, grammar and dirs
     * are reused from {@code src}, so re-running a cached config each cycle costs only a few string ops.
     */
    private PipelineConfig(PipelineConfig src, String runTimestamp) {
        this.identity = new Identity(src.identity.name(), src.identity.pipelineName(), runTimestamp);
        Dirs d = src.dirs;
        if (src.statusDirToPrepare != null && !src.statusDirToPrepare.isBlank()) {
            String pn = src.identity.pipelineName();
            String statusFile = Paths.get(src.statusDirToPrepare,
                    pn + "_status_" + runTimestamp + ".csv").toString();
            Path parent = Paths.get(statusFile).toAbsolutePath().getParent();
            this.dirs = new Dirs(d.poll(), d.database(), d.backup(), d.temp(), d.errors(),
                    d.quarantine(), d.markers(), d.logDir(),
                    statusFile,
                    parent.resolve(pn + "_batches_" + runTimestamp + ".csv").toString(),
                    parent.resolve(pn + "_lineage_" + runTimestamp + ".csv").toString(),
                    parent.resolve("manifests").toString(),
                    d.commitLogPath());   // persistent — never run-timestamped
        } else {
            this.dirs = d;   // literal status_file (or status disabled): nothing is run-timestamped
        }
        this.processing = src.processing;
        this.csv = src.csv;
        this.output = src.output;
        this.schemas = src.schemas;
        this.duckdb = src.duckdb;
        this.chunking = src.chunking;
        this.fixedWidth = src.fixedWidth;
        this.json = src.json;
        this.textRegex = src.textRegex;
        this.collector = src.collector;
        this.statusDirToPrepare = src.statusDirToPrepare;
        this.active = src.active;
        this.produces = src.produces;
        this.reference = src.reference;
        this.stream = src.stream;
        this.trigger = src.trigger;
        this.referencedFiles = src.referencedFiles;
    }

    /**
     * Return a copy of this config stamped with a fresh run timestamp (and the run-timestamped status
     * paths recomputed). Lets the orchestrator re-run a <em>cached</em> config every poll cycle — giving
     * each cycle its own status/batch/lineage CSVs — without re-parsing the file or re-reading schemas.
     * The status directory already exists (created by the original {@link #load}/{@link #prepare}), so
     * no directory creation is needed.
     */
    public PipelineConfig forNewRun() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return new PipelineConfig(this, ts);
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
        PipelineConfig cfg = PipelineConfigParser.parse(ToonHelper.load(configPath), configPath);
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
        return PipelineConfigParser.parse(raw, "<config>");
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

    // ── builder — package-private mutable accumulator; populated by PipelineConfigParser ──

    static final class Builder {
        String name          = "";
        String pipelineName  = "";
        String runTimestamp  = "";
        boolean active       = false;   // opt-in: a pipeline runs only with `active: true`
        Produces produces    = Produces.STREAM;   // catalog product; `produces: reference` ⇒ Reference Dataset
        Reference reference  = Reference.DEFAULT;  // `reference:` block; full-replace/no-key when absent
        String   stream;                           // logical Catalog Stream; parser defaults it to pipelineName
        Map<String, Object> trigger = null;   // optional entry-node trigger: block (T13); null ⇒ default poll
        final List<Path> referencedFiles = new ArrayList<>();   // schema/grammar/segment files read at parse
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
        Json         json;                // null unless frontend: json
        TextRegex    textRegex;           // null unless frontend: text_regex
        String       sourceId;
        String       collectorConnector = "local";
        List<String> sourceIncludes  = new ArrayList<>();
        List<String> sourceExcludes  = new ArrayList<>();
        int          sourceDepth     = -1;
        Stability    sourceStability = Stability.DISABLED;
        String       sourceConnection;   // null ⇒ no connection-profile binding (local)
        Duplicate    sourceDuplicate = Duplicate.PATH_DEFAULT;
        Guarantee    sourceGuarantee = Guarantee.BEST_EFFORT;
        GapDetection sourceGapDetection = GapDetection.DISABLED;
        Fetch           sourceFetch = Fetch.DEFAULT;
        Retry           sourceRetry = Retry.DISABLED;
        CircuitBreaker  sourceCircuitBreaker = CircuitBreaker.DISABLED;
        PostActionConfig sourcePostAction = PostActionConfig.RETAIN;
        Incremental     sourceIncremental = Incremental.DISABLED;
        String          sourceDiscovery = "poll";
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
