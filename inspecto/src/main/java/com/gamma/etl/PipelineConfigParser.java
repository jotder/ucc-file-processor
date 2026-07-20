package com.gamma.etl;

import com.gamma.etl.PipelineConfig.Builder;
import com.gamma.etl.PipelineConfig.CircuitBreaker;
import com.gamma.etl.PipelineConfig.Duplicate;
import com.gamma.etl.PipelineConfig.Fetch;
import com.gamma.etl.PipelineConfig.FixedWidth;
import com.gamma.etl.PipelineConfig.GapDetection;
import com.gamma.etl.PipelineConfig.Guarantee;
import com.gamma.etl.PipelineConfig.Incremental;
import com.gamma.etl.PipelineConfig.PostActionConfig;
import com.gamma.etl.PipelineConfig.Retry;
import com.gamma.etl.PipelineConfig.Stability;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Decodes a pipeline {@code .toon} config map into an immutable {@link PipelineConfig}.
 *
 * <p>This is the parsing/validation half of the config layer: it reads the raw decoded map,
 * resolves and validates schemas/grammar/directories, and populates a {@link PipelineConfig.Builder}.
 * {@link PipelineConfig} keeps only the immutable value object, its nested groups, and the public
 * {@link PipelineConfig#load load}/{@link PipelineConfig#fromMap fromMap} entry points (which delegate
 * here). The split keeps the value object readable and isolates the (larger, churnier) parsing logic.
 *
 * <p>A pure parse — no directory creation; {@link PipelineConfig#prepare()} performs the one
 * filesystem side-effect.
 */
final class PipelineConfigParser {

    // Log under PipelineConfig's category so existing log configuration/filtering is unaffected.
    private static final Logger log = LoggerFactory.getLogger(PipelineConfig.class);

    private PipelineConfigParser() {}

    @SuppressWarnings("unchecked")
    static PipelineConfig parse(Map<String, Object> raw, String sourceLabel) throws IOException {
        Builder b = new Builder();

        // ── identity ──────────────────────────────────────────────────────────
        b.name          = String.valueOf(raw.get("name"));
        b.pipelineName  = b.name.toLowerCase().replace(' ', '_');
        b.runTimestamp  = LocalDateTime.now()
                                       .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // ── activation gate (additive, v4.7.0; absent ⇒ false = not executed) ──
        // Only an activated pipeline is run by the poll cycle / MultiCollectorProcessor. The default is
        // OFF so a freshly-dropped or half-edited config never executes until explicitly armed.
        b.active = Boolean.parseBoolean(String.valueOf(raw.getOrDefault("active", "false")));

        // ── catalog product (additive, v5.1.0; absent ⇒ stream = today's behaviour) ──
        // 'produces: reference' registers this pipeline's output in the catalog as a standalone
        // Reference Dataset (dimension/lookup origin) instead of a Stream; enrichments bind it by name.
        Object produces = raw.get("produces");
        b.produces = PipelineConfig.Produces.from(produces == null ? null : produces.toString());

        // ── entry-node trigger (T13 / §3.6; absent ⇒ default poll = today's behaviour) ──
        // Carried verbatim; the live loop (CollectorService) classifies it via PipelineTrigger into
        // schedule(every/cron) / event / manual. Absent leaves the pipeline on the global poll cycle.
        if (raw.get("trigger") instanceof Map<?, ?> trig) b.trigger = (Map<String, Object>) trig;

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
        String grammarRef = blankToNull(proc.get("grammar"));
        if (grammarRef != null) b.referencedFiles.add(Paths.get(grammarRef));
        Map<String, Object> csv = resolveGrammar(proc);

        // ── unified parsing: block (additive, 4.8) ────────────────────────────
        // A top-level `parsing:` block is the design-of-record grammar (docs/parsing-options-reference.md
        // §5): `parsing.delimited` aliases today's `processing.csv_settings`, `parsing.plugin` aliases
        // `processing.ingester`/`segments`/`ingester_config`. Absent ⇒ nothing changes (every existing
        // config parses byte-for-byte identically). Keys from `parsing:` overlay the legacy blocks.
        Map<String, Object> parsing = (Map<String, Object>) raw.get("parsing");
        if (parsing != null) csv = mergeParsing(csv, parsing);

        String frontend = "delimited";
        if (csv != null) {
            frontend = frontendOf(csv);
            // json / text_regex inputs have no CSV header; unless the author explicitly set
            // has_header, don't skip their first record.
            if ((frontend.equals("json") || frontend.equals("text_regex"))
                    && !csv.containsKey("has_header"))
                csv.put("has_header", "false");
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
            // 4.8 additive: json / text_regex frontends (null unless selected)
            b.json             = parseJson(csv);
            b.textRegex        = parseTextRegex(csv);
        }

        // ── output ────────────────────────────────────────────────────────────
        Map<String, Object> out = (Map<String, Object>) raw.get("output");
        if (out != null) {
            b.outputFormat = String.valueOf(out.getOrDefault("format", "CSV")).toUpperCase();
            b.compression  = (String) out.get("compression");
            b.duckLakeCfg  = (Map<String, Object>) out.get("ducklake");
        }

        // ── plugin ingester + segments ────────────────────────────────────────
        // `parsing.plugin` (frontend: plugin) is the unified alias for the legacy
        // `processing.ingester`/`segments`/`ingester_config` triple; when present its keys win.
        Map<String, Object> pluginBlock =
                (parsing != null && parsing.get("plugin") instanceof Map<?, ?> pm)
                        ? (Map<String, Object>) pm : null;
        b.ingesterClass = pluginBlock != null && pluginBlock.get("ingester") != null
                ? (String) pluginBlock.get("ingester") : (String) proc.get("ingester");
        Object icfg = pluginBlock != null && pluginBlock.get("ingester_config") != null
                ? pluginBlock.get("ingester_config") : proc.get("ingester_config");
        if (icfg instanceof Map<?, ?> icfgMap)
            b.ingesterConfig = (Map<String, Object>) icfgMap;
        if (b.ingesterClass != null && !b.ingesterClass.isBlank()) {
            Object segsRaw = pluginBlock != null && pluginBlock.get("segments") != null
                    ? pluginBlock.get("segments") : proc.get("segments");
            if (!(segsRaw instanceof Map<?,?> segsMap) || segsMap.isEmpty())
                throw new IllegalArgumentException(
                        "parsing.plugin.segments (or processing.segments) must be a non-empty map "
                        + "when a plugin ingester is set");
            b.segmentSchemas = new LinkedHashMap<>();
            for (var entry : ((Map<?,?>) segsRaw).entrySet()) {
                String key        = (String) entry.getKey();
                String schemaPath = (String) entry.getValue();
                b.referencedFiles.add(Paths.get(schemaPath));
                if (!Files.exists(Paths.get(schemaPath)))
                    throw new FileNotFoundException("Segment schema not found for '" + key + "': " + schemaPath);
                Map<String, Object> schema = (Map<String, Object>)
                        JToon.decode(Files.readString(Paths.get(schemaPath), StandardCharsets.UTF_8));
                Identifiers.validateSchema(schema, "segment[" + key + "]");
                b.segmentSchemas.put(key, schema);
            }
            log.info("[CONFIG] Plugin ingester: {}  segments: {}",
                    b.ingesterClass, b.segmentSchemas.keySet());
        } else if (frontend.equals("plugin")) {
            throw new IllegalArgumentException(
                    "parsing.frontend 'plugin' requires parsing.plugin.ingester (or processing.ingester)");
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

                b.referencedFiles.add(Paths.get(schemaPath));
                if (!Files.exists(Paths.get(schemaPath)))
                    throw new FileNotFoundException("Schema file not found: " + schemaPath);
                Map<String, Object> schemaCfg = (Map<String, Object>)
                        JToon.decode(Files.readString(Paths.get(schemaPath), StandardCharsets.UTF_8));
                Identifiers.validateSchema(schemaCfg, "schemas[col=" + colCount + "]");
                if (table != null && !table.isBlank())
                    Identifiers.validate(table, "schemas[col=" + colCount + "].table");
                validateFixedWidthSelectors(b.fixedWidth, schemaCfg, "schemas[col=" + colCount + "]");
                validateTextRegexSelectors(b.textRegex, schemaCfg, "schemas[col=" + colCount + "]");

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
            // Legacy single-schema. OPTIONAL since v5.1.0: a draft (active: false) may not have
            // chosen its schema yet — it parses, indexes and shows in the catalog, but an armed
            // pipeline without any schema still fails fast (clear error, formerly an NPE here).
            String schemaPath = (String) proc.get("schema_file");
            if (schemaPath == null || schemaPath.isBlank()) {
                if (b.active)
                    throw new IllegalArgumentException("Config error in " + sourceLabel
                            + ": active: true but no schema is configured (processing.schema_file, "
                            + "processing.schemas[], or a plugin ingester) — keep a draft inactive "
                            + "until its schema is attached");
            } else {
                b.referencedFiles.add(Paths.get(schemaPath));
                if (!Files.exists(Paths.get(schemaPath)))
                    throw new FileNotFoundException("Schema file not found: " + schemaPath);
                b.singleSchema = (Map<String, Object>)
                        JToon.decode(Files.readString(Paths.get(schemaPath), StandardCharsets.UTF_8));
                Identifiers.validateSchema(b.singleSchema, "schema_file");
                validateFixedWidthSelectors(b.fixedWidth, b.singleSchema, "schema_file");
                validateTextRegexSelectors(b.textRegex, b.singleSchema, "schema_file");
            }
        }

        // ── source / connector (additive; absent ⇒ implicit LOCAL reading dirs.poll) ──────────────
        // A pipeline with no `source:` block scans the local poll dir exactly as before: the single
        // processing.file_pattern glob, no excludes, unbounded depth. A `source:` block selects a
        // connector and overrides discovery (include/exclude/recursive_depth).
        b.sourceId       = b.pipelineName;
        b.sourceIncludes = new ArrayList<>(List.of(b.filePattern));
        Map<String, Object> src = (Map<String, Object>) raw.get("collector");
        if (src != null) {
            b.sourceId        = opt(src, "id", b.pipelineName);
            b.collectorConnector = opt(src, "connector", "local").toLowerCase();
            List<String> inc  = strList(src.get("include"));
            if (!inc.isEmpty()) b.sourceIncludes = inc;
            b.sourceExcludes  = strList(src.get("exclude"));
            Object depth = src.get("recursive_depth");
            if (depth != null) b.sourceDepth = toInt(depth);
            // Reusable connection-profile binding (resolved against the service's *_connection.toon registry;
            // remote-connector construction from it is roadmap Phase E — the id is parsed/stored now).
            b.sourceConnection = opt(src, "connection", null);
            // ACQ-6 push discovery: poll (default) | watch (filesystem events on a local poll root).
            b.sourceDiscovery = opt(src, "discovery", "poll");

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

            // ── collection guarantee + gap detection (Phase D; additive, absent ⇒ best-effort / off) ──
            b.sourceGuarantee = Guarantee.from(opt(src, "guarantee", null));
            Map<String, Object> gap = (Map<String, Object>) src.get("gap_detection");
            if (gap != null) {
                boolean enabled = !"false".equalsIgnoreCase(
                        String.valueOf(gap.getOrDefault("enabled", "true")));
                String seq = opt(gap, "sequence", null);
                b.sourceGapDetection = new GapDetection(enabled, seq);
            }
            // A stronger-than-best-effort guarantee needs the fingerprint ledger to actually hold; without it
            // the engine falls back to commit-log replay + markers. Say so rather than silently over-promising.
            if (b.sourceGuarantee.requiresLedger() && !b.sourceDuplicate.contentBased())
                log.warn("[CONFIG] source.guarantee={} needs a fingerprint ledger, but source.duplicate.mode "
                        + "is 'path' (marker-only) — behaving as best-effort + commit-log replay. Set "
                        + "source.duplicate.mode to metadata, checksum or etag to enforce it.", b.sourceGuarantee);

            // ── retrieval tuning: parallel fetch + rate limit (Phase E/F; additive, absent ⇒ sequential/unthrottled) ──
            Map<String, Object> fetchBlock = (Map<String, Object>) src.get("fetch");
            if (fetchBlock != null) {
                b.sourceFetch = new Fetch(
                        opt(fetchBlock, "mode", "STAGE"),
                        opt(fetchBlock, "staging_dir", null),
                        Math.max(1, toInt(fetchBlock.getOrDefault("parallel_fetch", 1))),
                        parseRate(opt(fetchBlock, "rate_limit", null)));
            }

            // ── retry / backoff (Phase F; additive, absent ⇒ a single attempt) ──────────────────
            Map<String, Object> retryBlock = (Map<String, Object>) src.get("retry");
            if (retryBlock != null) {
                b.sourceRetry = new Retry(
                        toInt(retryBlock.getOrDefault("count", 0)),
                        opt(retryBlock, "backoff", "EXPONENTIAL"),
                        toMillis(opt(retryBlock, "initial_delay", "1s")),
                        toMillis(opt(retryBlock, "max_delay", "60s")));
            }

            // ── circuit breaker (Phase F; additive, absent ⇒ never trips) ───────────────────────
            Map<String, Object> cbBlock = (Map<String, Object>) src.get("circuit_breaker");
            if (cbBlock != null) {
                b.sourceCircuitBreaker = new CircuitBreaker(true,
                        Math.max(1, toInt(cbBlock.getOrDefault("failure_threshold", 5))),
                        toMillis(opt(cbBlock, "cooldown", "5m")));
            }

            // ── post-processing action (Phase F; additive, absent ⇒ RETAIN = leave the source) ──
            Map<String, Object> paBlock = (Map<String, Object>) src.get("post_action");
            if (paBlock != null) {
                Map<String, Object> rawTags = (Map<String, Object>) paBlock.get("tags");
                Map<String, String> tags = new java.util.LinkedHashMap<>();
                if (rawTags != null) rawTags.forEach((k, v) -> tags.put(k, String.valueOf(v)));
                b.sourcePostAction = new PostActionConfig(
                        opt(paBlock, "on_success", "RETAIN"),
                        opt(paBlock, "archive_path", null),
                        tags,
                        opt(paBlock, "on_unsupported", "WARN_AND_CONTINUE"));
            }

            // ── incremental discovery / high-watermark (Phase C4; additive, absent ⇒ full listing) ──
            Map<String, Object> incBlock = (Map<String, Object>) src.get("incremental");
            if (incBlock != null)
                b.sourceIncremental = new Incremental(opt(incBlock, "watermark", null));
            if (b.sourceIncremental.enabled() && !b.sourceDuplicate.contentBased())
                log.warn("[CONFIG] source.incremental.watermark is set but source.duplicate.mode is 'path' "
                        + "(marker-only) — the watermark is derived from the fingerprint ledger, which path mode "
                        + "does not populate, so incremental filtering will not engage. Set source.duplicate.mode "
                        + "to metadata, checksum or etag.");
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

    /**
     * Parse a transfer-rate string to <b>bytes per second</b> (Phase F {@code source.fetch.rate_limit}). Accepts a
     * size with an optional {@code /s} or {@code ps} suffix: {@code "50MBps"}, {@code "50MB/s"}, {@code "1GBps"},
     * {@code "512KBps"}, or a bare number (bytes/s). KB/MB/GB are binary (1024-based). {@code null}/blank ⇒ 0
     * (unlimited).
     */
    static long parseRate(String r) {
        if (r == null || r.isBlank()) return 0L;
        String s = r.trim();
        // strip a trailing per-second marker: "/s", "ps", "/sec"
        String lower = s.toLowerCase();
        if (lower.endsWith("/s"))   s = s.substring(0, s.length() - 2);
        else if (lower.endsWith("ps")) s = s.substring(0, s.length() - 2);
        else if (lower.endsWith("/sec")) s = s.substring(0, s.length() - 4);
        s = s.trim();
        long mult = 1L;
        String u = s.toUpperCase();
        if (u.endsWith("GB"))      { mult = 1L << 30; s = s.substring(0, s.length() - 2); }
        else if (u.endsWith("MB")) { mult = 1L << 20; s = s.substring(0, s.length() - 2); }
        else if (u.endsWith("KB")) { mult = 1L << 10; s = s.substring(0, s.length() - 2); }
        else if (u.endsWith("B"))  { s = s.substring(0, s.length() - 1); }
        s = s.trim();
        if (s.isEmpty()) return 0L;
        return (long) (Double.parseDouble(s) * mult);
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

    // ── unified parsing: block + json / text_regex frontends (4.8) ─────────────

    /** The recognised {@code parsing.frontend} values (docs/parsing-options-reference.md §5). */
    private static final Set<String> FRONTENDS =
            Set.of("delimited", "fixedwidth", "fixed_width", "json", "text_regex", "plugin");

    /**
     * Overlay the unified {@code parsing:} block onto the legacy grammar/{@code csv_settings} map
     * (which may be {@code null}). {@code parsing.delimited} keys land verbatim (it <em>is</em>
     * {@code csv_settings} under its canonical name); the shared {@code encoding}/{@code compression}
     * options and the {@code frontend} selector plus its per-frontend sub-block
     * ({@code fixedwidth}/{@code json}/{@code text_regex}) are copied through under the keys the
     * downstream parse already reads. Keys from {@code parsing:} win over the legacy block.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeParsing(Map<String, Object> base,
                                                    Map<String, Object> parsing) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) merged.putAll(base);
        if (parsing.get("delimited") instanceof Map<?, ?> del)
            merged.putAll((Map<String, Object>) del);
        for (String key : new String[]{"frontend", "encoding", "compression",
                                       "fixedwidth", "json", "text_regex"}) {
            Object v = parsing.get(key);
            if (v != null) merged.put(key, v);
        }
        return merged;
    }

    /** Resolve + validate the {@code frontend} selector; unknown values are rejected with the list. */
    private static String frontendOf(Map<String, Object> csv) {
        String f = String.valueOf(csv.getOrDefault("frontend", "delimited")).trim().toLowerCase();
        if (!FRONTENDS.contains(f))
            throw new IllegalArgumentException("Unknown parsing.frontend '" + f
                    + "' — expected one of: delimited, fixedwidth, json, text_regex, plugin");
        return f;
    }

    /**
     * Parse the optional JSON/NDJSON frontend. Returns {@code null} unless {@code frontend: json};
     * otherwise builds a {@link PipelineConfig.Json} from the (optional) {@code json:} block.
     * Hard-fails on an unknown {@code format} or an unsupported {@code records_path}.
     */
    @SuppressWarnings("unchecked")
    private static PipelineConfig.Json parseJson(Map<String, Object> csv) {
        if (!"json".equals(String.valueOf(csv.getOrDefault("frontend", "delimited")).trim().toLowerCase()))
            return null;
        Map<String, Object> j = (csv.get("json") instanceof Map<?, ?> jm)
                ? (Map<String, Object>) jm : Map.of();
        String format = opt(j, "format", "newline").trim().toLowerCase();
        if (!format.equals("newline") && !format.equals("array") && !format.equals("auto"))
            throw new IllegalArgumentException("json.format must be newline, array or auto (got '"
                    + format + "')");
        String recordsPath = opt(j, "records_path", "$").trim();
        if (!recordsPath.equals("$"))
            throw new IllegalArgumentException("json.records_path: only '$' (top-level records) is "
                    + "supported — unwrap nested record arrays upstream or use a plugin ingester");
        return new PipelineConfig.Json(format, recordsPath);
    }

    /**
     * Parse the optional text/regex frontend. Returns {@code null} unless {@code frontend: text_regex};
     * otherwise builds a {@link PipelineConfig.TextRegex} from the {@code text_regex:} block.
     * Hard-fails on a missing block/pattern, a pattern that does not compile, or a pattern without a
     * named capture group. {@code record_split} defaults to one-record-per-line; {@code "blank_line"}
     * (or a literal blank-line string) and any other literal delimiter string switch to block mode,
     * where a record may span multiple physical lines.
     */
    @SuppressWarnings("unchecked")
    private static PipelineConfig.TextRegex parseTextRegex(Map<String, Object> csv) {
        if (!"text_regex".equals(String.valueOf(csv.getOrDefault("frontend", "delimited")).trim().toLowerCase()))
            return null;
        if (!(csv.get("text_regex") instanceof Map<?, ?> trMap))
            throw new IllegalArgumentException(
                    "frontend 'text_regex' requires a 'text_regex:' block with a 'pattern'");
        Map<String, Object> tr = (Map<String, Object>) trMap;

        // Read raw (not via opt): a real "\n\n" is whitespace-only and must not fall back silently.
        Object rsRaw = tr.get("record_split");
        String recordSplitCfg = (rsRaw == null || String.valueOf(rsRaw).isEmpty())
                ? "\n" : String.valueOf(rsRaw);
        // accept the real newline, the literal two-char "\n" spelling, or the word "line"
        boolean lineSplit = recordSplitCfg.equals("\n") || recordSplitCfg.equals("\\n")
                || recordSplitCfg.equalsIgnoreCase("line");
        // "blank_line" is a named alias for the blank-line block delimiter; anything else not
        // recognised as line mode is taken literally as the block delimiter string (e.g. "---").
        String recordSplit = lineSplit ? "\n"
                : recordSplitCfg.equalsIgnoreCase("blank_line") ? "\n\n"
                : recordSplitCfg;

        String pattern = blankToNull(tr.get("pattern"));
        if (pattern == null)
            throw new IllegalArgumentException("text_regex.pattern is required");

        // Accept both the RE2 (?P<name>...) and Java (?<name>...) spellings. RE2 allows
        // underscores in group names where Java's engine does not, so validate compilation with
        // the named groups reduced to plain groups (checks everything else in the pattern), and
        // extract the names with a scan that allows the RE2 name grammar.
        String anonymised = pattern.replaceAll("\\(\\?P?<[A-Za-z][A-Za-z0-9_]*>", "(");
        try {
            java.util.regex.Pattern.compile(anonymised);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("text_regex.pattern does not compile: " + e.getMessage(), e);
        }
        List<String> groups = new ArrayList<>();
        java.util.regex.Matcher gm = java.util.regex.Pattern
                .compile("\\(\\?P?<([A-Za-z][A-Za-z0-9_]*)>").matcher(pattern);
        while (gm.find()) groups.add(gm.group(1));
        if (groups.isEmpty())
            throw new IllegalArgumentException("text_regex.pattern must contain at least one named "
                    + "capture group, e.g. (?P<key>[A-Z_]+) — group names feed raw.fields[].selector");
        // Normalise to the RE2 spelling for the generated SQL (DuckDB's regex engine).
        String sqlPattern = pattern.replaceAll("\\(\\?<(?![=!])", "(?P<");
        return new PipelineConfig.TextRegex(recordSplit, sqlPattern, groups);
    }

    /**
     * For the text/regex frontend, every schema {@code raw.fields[].selector} must name a declared
     * capture group — fail the load (clear message) if one does not. No-op for other frontends.
     */
    @SuppressWarnings("unchecked")
    private static void validateTextRegexSelectors(PipelineConfig.TextRegex tr,
                                                   Map<String, Object> schema, String label) {
        if (tr == null) return;
        List<Map<String, Object>> fields = (List<Map<String, Object>>)
                ((Map<String, Object>) schema.get("raw")).get("fields");
        for (Map<String, Object> f : fields) {
            String sel = String.valueOf(f.get("selector"));
            if (!tr.groupNames().contains(sel))
                throw new IllegalArgumentException(label + ": raw.fields selector '" + sel
                        + "' has no matching text_regex capture group (declared: " + tr.groupNames() + ")");
        }
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
}
