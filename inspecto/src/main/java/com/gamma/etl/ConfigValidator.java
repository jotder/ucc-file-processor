package com.gamma.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-load sanity checks for {@link PipelineConfig}.
 *
 * <p>Distinct from {@link Identifiers}: that class hard-fails on syntactic
 * problems with names. This class emits non-fatal warnings about configurations
 * that load successfully but are likely operator mistakes:
 *
 * <ul>
 *   <li>No partitioning declared (output collapses to the 1900/01/01 sentinel).</li>
 *   <li>Blank delimiter — a fall-through to the {@code ","} default that
 *       hides explicit-intent typos.</li>
 *   <li>Marker retention shorter than 1 day on a non-trivial pipeline.</li>
 *   <li>{@code threads} set to a value that's inconsistent with batch caps.</li>
 *   <li>CPU oversubscription — {@code sources.max × threads × duckdb_threads}
 *       exceeding the core count (explicit cap), or the auto cap's multi-source
 *       blind spot ({@code duckdb_threads=0} ignores {@code sources.max}).</li>
 *   <li>Plugin path with an empty {@code ingester_config} where a known-binary
 *       ingester class lives.</li>
 * </ul>
 *
 * <p>Warnings go through SLF4J at {@code WARN}; the run continues. Operators
 * see them at startup and can address them or ignore them with full knowledge.
 */
public final class ConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidator.class);

    private ConfigValidator() {}

    /**
     * Inspect {@code cfg} and log a warning for every suspicious-but-legal pattern.
     * Returns the list of warning messages emitted (for testing and audit reporting).
     */
    @SuppressWarnings("unchecked")
    public static List<String> validate(PipelineConfig cfg) {
        List<String> warnings = new ArrayList<>();

        // Partitioning declared? Each schema should produce a non-empty partition list.
        if (cfg.schemas().single() != null && PartitionDef.fromSchema(cfg.schemas().single()).isEmpty())
            warn(warnings, "No partitions[] or partitionKey on the single schema — " +
                    "all rows will land in the year=1900/month=01/day=01 sentinel partition.");

        if (cfg.schemas().segments() != null) {
            for (var e : cfg.schemas().segments().entrySet()) {
                if (PartitionDef.fromSchema(e.getValue()).isEmpty())
                    warn(warnings, "Segment '" + e.getKey() + "' has no partitions[] / partitionKey — " +
                            "rows for this segment will collapse to the sentinel partition.");
            }
        }

        // Delimiter: blank explicitly is suspicious (silently falls back to ",")
        if (cfg.csv().delimiter() == null || cfg.csv().delimiter().isEmpty())
            warn(warnings, "csv_settings.delimiter is blank — using fallback ','. " +
                    "Set an explicit value to silence this warning.");

        // Date formats: empty list means TRY_STRPTIME will always return NULL → DATE casts fail.
        if (cfg.csv().dateFormats() == null || cfg.csv().dateFormats().isEmpty())
            warn(warnings, "csv_settings.date_formats is empty — TRY_STRPTIME will return NULL for any DATE column.");
        if (cfg.csv().tsFormats() == null || cfg.csv().tsFormats().isEmpty())
            warn(warnings, "csv_settings.timestamp_formats is empty — TRY_STRPTIME will return NULL for any TIMESTAMP column.");

        // Marker retention: 0 or negative makes the cleanup delete every marker on first run.
        if (cfg.processing().duplicateCheckEnabled() && cfg.processing().retentionDays() <= 0)
            warn(warnings, "duplicate_check.enabled=true but retention_days=" + cfg.processing().retentionDays() +
                    " — every marker will be deleted on the next cleanup. Set retention_days >= 1.");

        // Threads vs batch caps: threads > 1 on a pipeline whose batch caps force one batch
        // per file is silently single-threaded (no parallelism on the batch level).
        if (cfg.processing().threads() > 1 && cfg.processing().batchMaxFiles() == 1)
            warn(warnings, "processing.threads=" + cfg.processing().threads() + " but batch.max_files=1 — " +
                    "each batch is a single file, so only " + cfg.processing().threads() + "-way file-level parallelism. " +
                    "Raise batch.max_files for intra-batch packing.");

        // CPU oversubscription: concurrent batches each open a DuckDB connection that,
        // capped by duckdb_threads, fans out to that many threads. The real worker
        // pressure is sources.max × threads × duckdb_threads (sources.max multiplies when
        // running under MultiSourceProcessor); its product exceeding the core count
        // oversubscribes the CPU and adds I/O contention.
        int cores = Runtime.getRuntime().availableProcessors();
        // sources.max is a -D JVM property read by MultiSourceProcessor; only present (and
        // > 1) when several sources share this JVM. Factor it in when explicitly set.
        Integer sourcesMaxProp = Integer.getInteger("sources.max");
        int srcFactor = (sourcesMaxProp != null && sourcesMaxProp > 1) ? sourcesMaxProp : 1;
        String srcPrefix = srcFactor > 1 ? "sources.max(" + srcFactor + ") × " : "";

        // (a) EXPLICIT positive duckdb_threads — the operator pinned a per-batch cap. The
        // default (0) auto-derives cores ÷ threads in BatchIngestStrategy.configure and -1
        // opts out, so the verbatim-product warning is scoped to explicit positive values.
        if (cfg.processing().duckdbThreads() > 0) {
            int total = srcFactor * cfg.processing().threads() * cfg.processing().duckdbThreads();
            if (total > cores)
                warn(warnings, srcPrefix + "processing.threads(" + cfg.processing().threads() + ") × duckdb_threads(" +
                        cfg.processing().duckdbThreads() + ") = " + total + " exceeds available cores (" + cores +
                        ") — concurrent batches may oversubscribe the CPU. Lower one so the product " +
                        "is ≈ cores.");
        }

        // (b) Multi-source blind spot for the auto cap: the default duckdb_threads=0 derives
        // cores ÷ threads WITHOUT knowledge of sources.max, so under MultiSourceProcessor with
        // sources.max > 1 the real concurrency is sources.max × threads batches and the auto cap
        // still oversubscribes by ~sources.max×. Surface the fix (set duckdb_threads explicitly
        // or lower sources.max) — this is the one case the auto-derive cannot self-manage.
        if (cfg.processing().duckdbThreads() == 0 && srcFactor > 1) {
            int suggested = Math.max(1, cores / (srcFactor * cfg.processing().threads()));
            warn(warnings, "sources.max=" + srcFactor + " with processing.duckdb_threads=0 (auto): the auto " +
                    "cap (cores ÷ threads) ignores sources.max, so " + srcFactor + " concurrent sources × " +
                    cfg.processing().threads() + " batch(es) can still oversubscribe the CPU. Set " +
                    "processing.duckdb_threads ≈ " + suggested + " (cores ÷ (sources.max × threads)) or lower sources.max.");
        }

        // Native DuckDB CSV engine forced on a config that strips phantom trailing
        // columns: DuckDB rejects too-many-column rows instead of trimming them,
        // so the row counts will differ from the Java path.
        if ("duckdb".equalsIgnoreCase(cfg.csv().engine()) && cfg.csv().skipTailCols() > 0)
            warn(warnings, "csv_settings.engine=duckdb with skip_tail_columns=" + cfg.csv().skipTailCols() +
                    " — the native reader rejects rows that have more columns than declared rather " +
                    "than trimming them; row counts may differ from the Java parser. Use engine=java " +
                    "or engine=auto if those rows must be retained.");

        // json / text_regex frontends: the index-anchored CSV row filters target physical c<N>
        // columns that these frontends do not produce — the generated SQL would fail at ingest.
        if ((cfg.json() != null || cfg.textRegex() != null) && cfg.csv().hasRowFilters())
            warn(warnings, "include/exclude row filters target delimited c<N> columns and are not " +
                    "supported by the " + (cfg.json() != null ? "json" : "text_regex") +
                    " frontend — ingest will fail. Filter via mapping rules instead.");

        // Fixed-width frontend: overlapping slices (fields sharing bytes) is almost always a mistake.
        if (cfg.fixedWidth() != null) {
            List<PipelineConfig.FixedWidth.Slice> sorted = new ArrayList<>(cfg.fixedWidth().slices());
            sorted.sort(java.util.Comparator.comparingInt(PipelineConfig.FixedWidth.Slice::start));
            for (int i = 1; i < sorted.size(); i++) {
                PipelineConfig.FixedWidth.Slice prev = sorted.get(i - 1), cur = sorted.get(i);
                if (cur.start() < prev.start() + prev.length())
                    warn(warnings, "fixedwidth slices overlap at byte " + cur.start() + " — '" + prev.name() +
                            "' and '" + cur.name() + "' share bytes; check each field's start/length.");
            }
        }

        return warnings;
    }

    private static void warn(List<String> sink, String msg) {
        log.warn("[CONFIG] {}", msg);
        sink.add(msg);
    }
}
