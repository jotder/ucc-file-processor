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
        if (cfg.singleSchema != null && PartitionDef.fromSchema(cfg.singleSchema).isEmpty())
            warn(warnings, "No partitions[] or partitionKey on the single schema — " +
                    "all rows will land in the year=1900/month=01/day=01 sentinel partition.");

        if (cfg.segmentSchemas != null) {
            for (var e : cfg.segmentSchemas.entrySet()) {
                if (PartitionDef.fromSchema(e.getValue()).isEmpty())
                    warn(warnings, "Segment '" + e.getKey() + "' has no partitions[] / partitionKey — " +
                            "rows for this segment will collapse to the sentinel partition.");
            }
        }

        // Delimiter: blank explicitly is suspicious (silently falls back to ",")
        if (cfg.delimiter == null || cfg.delimiter.isEmpty())
            warn(warnings, "csv_settings.delimiter is blank — using fallback ','. " +
                    "Set an explicit value to silence this warning.");

        // Date formats: empty list means TRY_STRPTIME will always return NULL → DATE casts fail.
        if (cfg.dateFormats == null || cfg.dateFormats.isEmpty())
            warn(warnings, "csv_settings.date_formats is empty — TRY_STRPTIME will return NULL for any DATE column.");
        if (cfg.tsFormats == null || cfg.tsFormats.isEmpty())
            warn(warnings, "csv_settings.timestamp_formats is empty — TRY_STRPTIME will return NULL for any TIMESTAMP column.");

        // Marker retention: 0 or negative makes the cleanup delete every marker on first run.
        if (cfg.duplicateCheckEnabled && cfg.retentionDays <= 0)
            warn(warnings, "duplicate_check.enabled=true but retention_days=" + cfg.retentionDays +
                    " — every marker will be deleted on the next cleanup. Set retention_days >= 1.");

        // Threads vs batch caps: threads > 1 on a pipeline whose batch caps force one batch
        // per file is silently single-threaded (no parallelism on the batch level).
        if (cfg.threads > 1 && cfg.batchMaxFiles == 1)
            warn(warnings, "processing.threads=" + cfg.threads + " but batch.max_files=1 — " +
                    "each batch is a single file, so only " + cfg.threads + "-way file-level parallelism. " +
                    "Raise batch.max_files for intra-batch packing.");

        // CPU oversubscription: concurrent batches (threads) each open a DuckDB connection
        // that, capped by duckdb_threads, fans out to that many threads. Their product
        // exceeding the core count oversubscribes the CPU and adds I/O contention.
        if (cfg.duckdbThreads > 0) {
            int cores = Runtime.getRuntime().availableProcessors();
            int total = cfg.threads * cfg.duckdbThreads;
            if (total > cores)
                warn(warnings, "processing.threads(" + cfg.threads + ") × duckdb_threads(" +
                        cfg.duckdbThreads + ") = " + total + " exceeds available cores (" + cores +
                        ") — concurrent batches may oversubscribe the CPU. Lower one so the product " +
                        "is ≈ cores.");
        }

        // Native DuckDB CSV engine forced on a config that strips phantom trailing
        // columns: DuckDB rejects too-many-column rows instead of trimming them,
        // so the row counts will differ from the Java path.
        if ("duckdb".equalsIgnoreCase(cfg.csvEngine) && cfg.skipTailCols > 0)
            warn(warnings, "csv_settings.engine=duckdb with skip_tail_columns=" + cfg.skipTailCols +
                    " — the native reader rejects rows that have more columns than declared rather " +
                    "than trimming them; row counts may differ from the Java parser. Use engine=java " +
                    "or engine=auto if those rows must be retained.");

        return warnings;
    }

    private static void warn(List<String> sink, String msg) {
        log.warn("[CONFIG] {}", msg);
        sink.add(msg);
    }
}
