package com.gamma.config.spec;

import com.gamma.api.PublicApi;

import java.util.List;
import java.util.Map;

import static com.gamma.config.spec.RawConfig.at;
import static com.gamma.config.spec.RawConfig.boolOr;
import static com.gamma.config.spec.RawConfig.intOr;
import static com.gamma.config.spec.RawConfig.present;
import static com.gamma.config.spec.RawConfig.str;

/**
 * The authored {@link ConfigSpec}s for every configuration type the system loads.
 *
 * <p>Each spec encodes — as data — the rules that today live implicitly in the {@code load} methods
 * ({@code PipelineConfig}, {@code EnrichmentConfig}, {@code JobConfig}) and in {@code ConfigValidator}.
 * The cross-field predicates are deliberately copied from those sources so the declarative spec and
 * the imperative loaders agree; the matching unit tests assert that equivalence on good/bad maps.
 *
 * <p>Fields are addressed by dotted path (see {@link RawConfig}); the paths match the nested
 * structure of the corresponding {@code .toon} file exactly.
 */
@PublicApi(since = "4.0.0")
public final class ConfigSpecs {

    private ConfigSpecs() {}

    /** Spec types in canonical order — also the set accepted by {@code GET /config/spec/{type}}. */
    public static final List<String> TYPES =
            List.of("pipeline", "enrichment", "job", "schema", "meta", "alert", "expectation",
                    "widget", "dashboard");

    /** The {@link ConfigSpec} for {@code type}, or {@code null} if {@code type} is unknown. */
    public static ConfigSpec forType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toLowerCase()) {
            case "pipeline"   -> pipeline();
            case "enrichment" -> enrichment();
            case "job"        -> job();
            case "schema"     -> schema();
            case "meta"       -> meta();
            case "alert"      -> alert();
            case "expectation" -> expectation();
            case "widget"     -> widget();
            case "dashboard"  -> dashboard();
            default           -> null;
        };
    }

    // ── pipeline ────────────────────────────────────────────────────────────────

    public static ConfigSpec pipeline() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Pipeline name", FieldType.STRING,
                        "Display name; the lowercased, underscored form is the pipeline's stable id."),
                FieldSpec.enumField("produces", "Produces", List.of("stream", "reference"), "stream",
                        "What the output registers as in the Catalog: an event/fact Stream (default) or a "
                                + "Reference Dataset (dimension/lookup) that enrichments can bind by name."),
                FieldSpec.required("dirs.poll", "Poll directory", FieldType.FILEPATH,
                        "Directory watched for incoming files. All managed dirs must live outside it."),
                FieldSpec.required("dirs.database", "Output database directory", FieldType.FILEPATH,
                        "Root of the Stage-1 partitioned output."),
                FieldSpec.of("dirs.backup", "Backup directory", FieldType.FILEPATH,
                        "Where processed source files are moved after a successful run."),
                FieldSpec.of("dirs.temp", "Temp directory", FieldType.FILEPATH, "Scratch space for a run."),
                FieldSpec.of("dirs.status_dir", "Status directory", FieldType.FILEPATH,
                        "Directory for run audit CSVs; a timestamped status file is created here."),
                FieldSpec.withDefault("processing.threads", "Batch concurrency", FieldType.INT, 4,
                        "Concurrent batches (semaphore permits over a virtual-thread executor)."),
                FieldSpec.withDefault("processing.duckdb_threads", "DuckDB threads/batch", FieldType.INT, 0,
                        "Per-batch DuckDB parallelism (PRAGMA threads): 0 = auto (cores ÷ threads, avoids "
                                + "oversubscription), -1 = DuckDB default (all cores per batch), N = exactly N."),
                FieldSpec.withDefault("processing.file_pattern", "File pattern", FieldType.STRING,
                        "glob:**/*.{csv,csv.gz}", "Glob selecting source files under the poll dir."),
                FieldSpec.of("processing.schema_file", "Schema file", FieldType.FILEPATH,
                        "Path to the *_schema.toon holding field selectors/types/partition keys. A runnable "
                                + "pipeline needs this, a schemas[] dispatch list, or a plugin ingester."),
                FieldSpec.of("processing.ingester", "Plugin ingester class", FieldType.STRING,
                        "FQCN of a plugin ingester; when set, processing.segments must be non-empty."),
                FieldSpec.of("processing.grammar", "Delimited grammar file", FieldType.FILEPATH,
                        "Path to a reusable *.grammar.toon holding the delimited parse settings (the same keys "
                                + "as processing.csv_settings). Inline csv_settings keys override the grammar file."),
                FieldSpec.enumField("processing.csv_settings.engine", "CSV engine",
                        List.of("auto", "duckdb", "java"), "auto",
                        "auto uses DuckDB's native reader for clean configs and the Java parser otherwise."),
                FieldSpec.withDefault("processing.csv_settings.delimiter", "Delimiter", FieldType.STRING, ",",
                        "Field delimiter; a blank value silently falls back to ','."),
                FieldSpec.withDefault("processing.csv_settings.has_header", "Has header", FieldType.BOOL, true,
                        "Whether source files carry a header row."),
                FieldSpec.of("processing.csv_settings.encoding", "Encoding", FieldType.STRING,
                        "Source charset for the native reader (e.g. utf-8, latin-1, utf-16); blank = utf-8."),
                FieldSpec.of("processing.csv_settings.compression", "Input compression", FieldType.STRING,
                        "read_csv input compression (auto/gzip/zstd/none); blank = auto-detect by extension."),
                FieldSpec.of("processing.csv_settings.strict_mode", "Strict mode", FieldType.BOOL,
                        "DuckDB strict_mode; blank = DuckDB default (true). false tolerates quote/column drift."),
                FieldSpec.of("processing.csv_settings.null_strings", "Null strings", FieldType.LIST,
                        "Literal text values read as SQL NULL (read_csv nullstr), e.g. ['', 'NULL', 'NaN']."),
                FieldSpec.of("processing.csv_settings.include_prefixes", "Include prefixes", FieldType.LIST,
                        "Row allow-list: keep rows whose filter_target_column starts with any of these."),
                FieldSpec.of("processing.csv_settings.include_regex", "Include regex", FieldType.LIST,
                        "Row allow-list (regexp_matches): keep rows whose filter_target_column matches any."),
                FieldSpec.of("processing.csv_settings.exclude_prefixes", "Exclude prefixes", FieldType.LIST,
                        "Row deny-list: drop rows whose filter_target_column starts with any of these."),
                FieldSpec.of("processing.csv_settings.exclude_regex", "Exclude regex", FieldType.LIST,
                        "Row deny-list (regexp_matches): drop rows whose filter_target_column matches any."),
                FieldSpec.withDefault("processing.csv_settings.filter_target_column", "Filter target column",
                        FieldType.INT, 0, "0-based selector index the include/exclude filters apply to."),
                FieldSpec.withDefault("processing.batch.max_files", "Batch max files", FieldType.INT, 1,
                        "Files packed into one batch; raise above 1 for intra-batch parallelism."),
                FieldSpec.of("processing.duckdb.temp_directory", "DuckDB scratch dir", FieldType.FILEPATH,
                        "Directory for the per-batch temp DB and DuckDB spill; defaults to dirs.temp (never the system /tmp). Point at the roomiest disk for very large files."),
                FieldSpec.of("processing.duckdb.memory_limit", "DuckDB memory limit", FieldType.STRING,
                        "RAM cap per worker connection (DuckDB size string, e.g. '16GB'); beyond it DuckDB spills to temp_directory. Blank = DuckDB default (~80% RAM)."),
                FieldSpec.of("processing.duckdb.max_temp_directory_size", "DuckDB spill cap", FieldType.STRING,
                        "Hard cap on spill size (e.g. '900GB') so a runaway query fails fast instead of filling the disk."),
                FieldSpec.withDefault("processing.chunking.max_file_bytes", "Auto-chunk threshold (bytes)", FieldType.LONG, 0L,
                        "Files larger than this are streamed in bounded chunks to cap scratch; 0 = disabled."),
                FieldSpec.of("processing.chunking.target_chunk_bytes", "Target chunk size (bytes)", FieldType.LONG,
                        "Approximate size of each chunk when chunking is active; defaults to the threshold."),
                FieldSpec.withDefault("processing.streaming.large_file_bytes", "Streaming generation-mode threshold (bytes)",
                        FieldType.LONG, 268_435_456L,
                        "Plugin-ingester batches whose largest member is >= this run in bounded generation mode (huge files); smaller batches use union mode (many small files packed → one transform/write). 0 = always union."),
                FieldSpec.withDefault("processing.streaming.flush_records", "Streaming generation row budget",
                        FieldType.LONG, 5_000_000L,
                        "Rows per generation flush in generation mode; bounds scratch per generation."),
                FieldSpec.enumField("output.format", "Output format",
                        List.of("CSV", "PARQUET"), "CSV", "Stage-1 output file format."),
                FieldSpec.of("output.compression", "Output compression", FieldType.STRING,
                        "Codec for the output (e.g. snappy); blank = format default.")
        );

        int cores = Runtime.getRuntime().availableProcessors();
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "plugin-ingester-requires-segments",
                        "processing.segments must be a non-empty map when processing.ingester is set.",
                        Severity.ERROR,
                        List.of("processing.ingester", "processing.segments"),
                        raw -> {
                            String ing = str(raw, "processing.ingester");
                            if (ing == null || ing.isBlank()) {
                                return true;
                            }
                            Object segs = at(raw, "processing.segments");
                            return segs instanceof Map<?, ?> m && !m.isEmpty();
                        }),
                new CrossFieldRule(
                        "threads-x-duckdb-threads-oversubscription",
                        "processing.threads × processing.duckdb_threads should not exceed available cores ("
                                + cores + ") — concurrent batches may oversubscribe the CPU.",
                        Severity.WARNING,
                        List.of("processing.threads", "processing.duckdb_threads"),
                        raw -> {
                            int d = intOr(raw, "processing.duckdb_threads", 0);
                            if (d <= 0) {
                                return true;
                            }
                            int t = intOr(raw, "processing.threads", 4);
                            return (long) t * d <= cores;
                        }),
                new CrossFieldRule(
                        "duckdb-engine-x-skip-tail-columns",
                        "csv_settings.engine=duckdb with skip_tail_columns>0 — the native reader rejects "
                                + "over-wide rows rather than trimming them; use engine=java/auto to retain them.",
                        Severity.WARNING,
                        List.of("processing.csv_settings.engine", "processing.csv_settings.skip_tail_columns"),
                        raw -> {
                            boolean duckdb = "duckdb".equalsIgnoreCase(str(raw, "processing.csv_settings.engine"));
                            int skipTail = intOr(raw, "processing.csv_settings.skip_tail_columns", 0);
                            return !(duckdb && skipTail > 0);
                        }),
                new CrossFieldRule(
                        "threads-vs-batch-max-files",
                        "processing.threads>1 with batch.max_files=1 yields only file-level parallelism; "
                                + "raise batch.max_files for intra-batch packing.",
                        Severity.WARNING,
                        List.of("processing.threads", "processing.batch.max_files"),
                        raw -> {
                            int t = intOr(raw, "processing.threads", 4);
                            int maxFiles = intOr(raw, "processing.batch.max_files", 1);
                            return !(t > 1 && maxFiles == 1);
                        }),
                new CrossFieldRule(
                        "duplicate-check-retention",
                        "duplicate_check.enabled=true with retention_days<=0 deletes every marker on the next "
                                + "cleanup; set retention_days >= 1.",
                        Severity.WARNING,
                        List.of("processing.duplicate_check.enabled", "processing.duplicate_check.retention_days"),
                        raw -> {
                            boolean enabled = boolOr(raw, "processing.duplicate_check.enabled", false);
                            int retention = intOr(raw, "processing.duplicate_check.retention_days", 90);
                            return !(enabled && retention <= 0);
                        })
        );
        return new ConfigSpec("pipeline", fields, rules);
    }

    // ── enrichment ──────────────────────────────────────────────────────────────

    public static ConfigSpec enrichment() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Enrichment name", FieldType.STRING, "Stable id for the Stage-2 job."),
                FieldSpec.required("input.database", "Input database", FieldType.FILEPATH,
                        "Root of the Stage-1 Hive-partitioned output to read."),
                FieldSpec.enumField("input.format", "Input format", List.of("PARQUET", "CSV"), "PARQUET",
                        "Format of the Stage-1 output."),
                FieldSpec.of("input.partitions", "Input partitions", FieldType.LIST,
                        "Hive partition columns present on the input."),
                FieldSpec.required("output.database", "Output database", FieldType.FILEPATH,
                        "Where enriched output is written."),
                FieldSpec.enumField("output.format", "Output format", List.of("PARQUET", "CSV"), "PARQUET",
                        "Enriched output format."),
                FieldSpec.of("output.compression", "Output compression", FieldType.STRING,
                        "Codec for the output (e.g. snappy)."),
                FieldSpec.of("output.partitions", "Output partitions", FieldType.LIST,
                        "Output grain; may differ from the input grain."),
                FieldSpec.of("transform", "Transform SQL", FieldType.SQL,
                        "Inline SQL reading from the 'input' view and any references; or use transform_file."),
                FieldSpec.of("transform_file", "Transform SQL file", FieldType.FILEPATH,
                        "Path to a .sql file used when 'transform' is absent."),
                FieldSpec.of("triggers.on_pipeline", "Trigger pipeline", FieldType.STRING,
                        "Upstream pipeline/enrichment whose commit triggers an incremental recompute."),
                FieldSpec.of("triggers.schedule_seconds", "Schedule seconds", FieldType.LONG,
                        "Interval for a full completeness recompute; <=0 disables.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "transform-or-transform-file",
                        "An enrichment needs either an inline 'transform' or a 'transform_file'.",
                        Severity.ERROR,
                        List.of("transform", "transform_file"),
                        raw -> present(raw, "transform") || present(raw, "transform_file"))
        );
        return new ConfigSpec("enrichment", fields, rules);
    }

    // ── job ───────────────────────────────────────────────────────────────────

    public static ConfigSpec job() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("job.name", "Job name", FieldType.STRING, "Unique job name."),
                FieldSpec.required("job.type", "Job type", FieldType.STRING,
                        "The Job Type id — an open registry key (built-ins: enrich, report, maintenance, "
                                + "pipeline; plus any module/pack-provided id). See GET /jobs/types."),
                new FieldSpec("job.cron", "Cron", "Calendar schedule (5 or 6 cron fields), or blank.",
                        FieldType.CRON, false, null, List.of(), null, "cron-editor", null),
                FieldSpec.of("job.on_pipeline", "Trigger pipeline", FieldType.STRING,
                        "Run when this upstream pipeline/job commits a batch."),
                FieldSpec.withDefault("job.enabled", "Enabled", FieldType.BOOL, true,
                        "Whether the scheduler arms this job.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "job-type-required",
                        "job.type must be one of enrich|report|maintenance.",
                        Severity.ERROR,
                        List.of("job.type"),
                        raw -> {
                            String t = str(raw, "job.type");
                            return t != null && List.of("enrich", "report", "maintenance")
                                    .contains(t.toLowerCase());
                        }),
                new CrossFieldRule(
                        "cron-field-count",
                        "job.cron, when present, must have 5 or 6 whitespace-separated fields.",
                        Severity.ERROR,
                        List.of("job.cron"),
                        raw -> {
                            String cron = str(raw, "job.cron");
                            if (cron == null || cron.isBlank()) {
                                return true;
                            }
                            int n = cron.trim().split("\\s+").length;
                            return n == 5 || n == 6;
                        })
        );
        return new ConfigSpec("job", fields, rules);
    }

    // ── alert (v4.1, B5) ─────────────────────────────────────────────────────────

    /** The {@code *_alert.toon} rule executed by the core alert engine (drafted by diagnose-and-alert). */
    public static ConfigSpec alert() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("alert.name", "Rule name", FieldType.STRING,
                        "Unique, kebab-case alert rule name."),
                FieldSpec.enumField("alert.metric", "Metric",
                        List.of("error_rate", "failed_batches", "rejected_files", "duration_ms"), null,
                        "The batches-ledger metric the rule watches."),
                FieldSpec.enumField("alert.comparator", "Comparator",
                        List.of("gt", "gte", "lt", "lte"), "gt", "How the value meets the threshold."),
                FieldSpec.required("alert.threshold", "Threshold", FieldType.STRING,
                        "Positive number; error_rate is a fraction in (0, 1]."),
                FieldSpec.required("alert.window", "Window", FieldType.STRING,
                        "Ns/Nm/Nh/Nd elapsed time, or Nb = the last N batches (e.g. 1h, 30m, 20b)."),
                FieldSpec.enumField("alert.severity", "Severity",
                        List.of("INFO", "WARNING", "CRITICAL"), "WARNING", "Operator-facing severity."),
                FieldSpec.of("alert.onPipeline", "Pipeline", FieldType.STRING,
                        "Restrict to one pipeline (display or normalized name); blank = every pipeline.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "alert-threshold-positive",
                        "alert.threshold must be a positive number.",
                        Severity.ERROR,
                        List.of("alert.threshold"),
                        raw -> {
                            String t = str(raw, "alert.threshold");
                            if (t == null) {
                                return false;
                            }
                            try {
                                return Double.parseDouble(t.trim()) > 0;
                            } catch (NumberFormatException e) {
                                return false;
                            }
                        }),
                new CrossFieldRule(
                        "alert-window-shape",
                        "alert.window must match \\d+[smhdb] (e.g. 1h, 30m, 20b).",
                        Severity.ERROR,
                        List.of("alert.window"),
                        raw -> {
                            String w = str(raw, "alert.window");
                            return w != null && w.trim().toLowerCase().matches("\\d+[smhdb]");
                        })
        );
        return new ConfigSpec("alert", fields, rules);
    }

    // ── expectation (ING-6) ──────────────────────────────────────────────────────

    /** The authored data-quality {@code expectation} component evaluated by the Expectation engine. */
    public static ConfigSpec expectation() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Expectation name", FieldType.STRING,
                        "Unique name for the data-quality check."),
                FieldSpec.of("description", "Description", FieldType.STRING, "What this check asserts."),
                FieldSpec.enumField("targetType", "Target type", List.of("pipeline", "job"), "pipeline",
                        "Whether the target's at-rest data comes from a pipeline or a job."),
                FieldSpec.required("target", "Target", FieldType.STRING,
                        "Name of the pipeline/job whose at-rest Parquet is scanned."),
                FieldSpec.required("column", "Column", FieldType.STRING, "The column the check applies to."),
                FieldSpec.enumField("kind", "Kind",
                        List.of("non_null", "range", "regex", "referential"), "non_null",
                        "The data-quality constraint: not-null, numeric range, regex match, or referential lookup."),
                FieldSpec.of("min", "Min", FieldType.STRING, "Range lower bound (range kind)."),
                FieldSpec.of("max", "Max", FieldType.STRING, "Range upper bound (range kind)."),
                FieldSpec.of("pattern", "Pattern", FieldType.STRING, "Regex the value must match (regex kind)."),
                FieldSpec.of("refDataset", "Reference dataset", FieldType.STRING,
                        "Lookup relation the value must exist in (referential kind)."),
                FieldSpec.of("refColumn", "Reference column", FieldType.STRING,
                        "Column in the reference dataset (referential kind)."),
                FieldSpec.enumField("severity", "Severity", List.of("MINOR", "MAJOR", "CRITICAL"), "MAJOR",
                        "Severity of the Incident raised on failure."),
                FieldSpec.withDefault("enabled", "Enabled", FieldType.BOOL, true,
                        "Whether evaluate-all includes this expectation.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "range-needs-a-bound",
                        "A range expectation needs at least one of min/max.",
                        Severity.ERROR,
                        List.of("kind", "min", "max"),
                        raw -> !"range".equalsIgnoreCase(str(raw, "kind"))
                                || present(raw, "min") || present(raw, "max")),
                new CrossFieldRule(
                        "regex-needs-a-pattern",
                        "A regex expectation needs a pattern.",
                        Severity.ERROR,
                        List.of("kind", "pattern"),
                        raw -> !"regex".equalsIgnoreCase(str(raw, "kind")) || present(raw, "pattern")),
                new CrossFieldRule(
                        "referential-needs-ref",
                        "A referential expectation needs refDataset and refColumn.",
                        Severity.ERROR,
                        List.of("kind", "refDataset", "refColumn"),
                        raw -> !"referential".equalsIgnoreCase(str(raw, "kind"))
                                || (present(raw, "refDataset") && present(raw, "refColumn")))
        );
        return new ConfigSpec("expectation", fields, rules);
    }

    // ── widget ────────────────────────────────────────────────────────────────────

    /** A saved visualization: a viz-plugin {@code vizType} + a dataset (or a saved view) + the
     *  field→channel {@code controls} the plugin compiles to a query. The {@code controls} shape is
     *  plugin-defined (an open map), so this spec validates the envelope, not the per-viz channel keys;
     *  the one hard rule is that a widget must bind a dataset or a view. Mirrors {@code widget-types.ts}. */
    public static ConfigSpec widget() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("vizType", "Visualization type", FieldType.STRING,
                        "The viz plugin type: kpi, bar, line, pie, table, geo-map, link-analysis, …"),
                FieldSpec.of("datasetId", "Dataset", FieldType.STRING,
                        "The dataset the widget queries (empty for a view-bound widget, which uses viewId)."),
                FieldSpec.of("viewId", "View", FieldType.STRING,
                        "A saved investigation view (geo-map/link-analysis) rendered instead of a dataset query."),
                FieldSpec.of("queryId", "Query", FieldType.STRING,
                        "A saved query component that supplies the rows instead of the dataset's own columns."),
                FieldSpec.of("controls", "Field mapping", FieldType.MAP,
                        "The field→channel mapping the viz plugin compiles to a query (e.g. value / x / y / series)."),
                FieldSpec.of("options", "Advanced options", FieldType.MAP, "Caption/title and render options."),
                FieldSpec.of("tags", "Tags", FieldType.LIST, "Free-text tags for the widget gallery."),
                FieldSpec.of("description", "Description", FieldType.STRING, "Library-card subtitle.")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "binds-a-dataset-or-a-view",
                        "A widget must bind a dataset (datasetId) or a saved view (viewId).",
                        Severity.ERROR,
                        List.of("datasetId", "viewId"),
                        raw -> present(raw, "datasetId") || present(raw, "viewId"))
        );
        return new ConfigSpec("widget", fields, rules);
    }

    // ── dashboard ─────────────────────────────────────────────────────────────────

    /** A composite of saved widgets laid out in a grid, with an optional dashboard-level cross-filter
     *  injected into every tile's query. Each tile references a widget by id + a 1|2 column span; deep
     *  per-tile validation and widget-existence checks belong in the caller, not this envelope spec.
     *  Mirrors {@code dashboard-types.ts}. */
    public static ConfigSpec dashboard() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("tiles", "Tiles", FieldType.LIST,
                        "Placed widgets — each {widgetId, span:1|2}; array order is the layout order."),
                FieldSpec.of("filter", "Cross-filter", FieldType.MAP,
                        "A Query Core condition group injected into every tile's query."),
                FieldSpec.of("exposedFields", "Exposed filter fields", FieldType.LIST,
                        "Columns offered to viewers as quick filters (the dashboard filter bar).")
        );
        List<CrossFieldRule> rules = List.of(
                new CrossFieldRule(
                        "at-least-one-tile",
                        "A dashboard needs at least one tile.",
                        Severity.ERROR,
                        List.of("tiles"),
                        raw -> {
                            Object tiles = at(raw, "tiles");
                            return !(tiles instanceof List<?> l) || !l.isEmpty();
                        })
        );
        return new ConfigSpec("dashboard", fields, rules);
    }

    // ── schema ──────────────────────────────────────────────────────────────────

    public static ConfigSpec schema() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("raw.name", "Schema name", FieldType.STRING, "Logical name of the raw source."),
                FieldSpec.enumField("raw.format", "Raw format", List.of("CSV", "PARQUET"), "CSV",
                        "Source file format."),
                FieldSpec.of("partitionKey", "Partition key", FieldType.STRING,
                        "Column whose value drives Hive partitioning (or use fields[].partitions)."),
                FieldSpec.of("raw.fields", "Field definitions", FieldType.LIST,
                        "Tabular array of {name,selector,type[,description,unit,classification]}."),
                FieldSpec.of("mapping.canonicalName", "Canonical name", FieldType.STRING,
                        "Canonical table name the raw source maps to.")
        );
        // Schema bodies are deeply validated by Identifiers.validateSchema at parse time; the spec
        // describes shape for UI/AI rather than re-implementing that structural validation.
        return new ConfigSpec("schema", fields, List.of());
    }

    // ── meta (KPI/report semantics) ──────────────────────────────────────────────

    public static ConfigSpec meta() {
        List<FieldSpec> fields = List.of(
                FieldSpec.required("name", "Semantics name", FieldType.STRING, "Name of this semantic model."),
                FieldSpec.of("version", "Version", FieldType.INT, "Schema version of the meta file."),
                FieldSpec.of("tables", "Table descriptions", FieldType.MAP,
                        "Map of table id → {description, grain}."),
                FieldSpec.of("kpis", "KPI catalog", FieldType.MAP,
                        "Map of KPI name → {definition, grain, inputs[], join_keys[]}."),
                FieldSpec.of("reports", "Report catalog", FieldType.MAP,
                        "Map of report name → {description, uses[]}."),
                FieldSpec.of("domain", "Domain notes", FieldType.MAP,
                        "{currency, timezone, notes[]} — cross-cutting domain context.")
        );
        return new ConfigSpec("meta", fields, List.of());
    }
}
