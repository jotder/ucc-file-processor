package com.gamma.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Runs an arbitrary SQL summarization query against one or more Parquet files
 * via DuckDB and writes the result to a single Parquet or CSV output file.
 *
 * <p>The SQL query references the virtual table {@code input}, which resolves to
 * all Parquet files matched by the configured glob. This lets callers write
 * natural SQL such as:
 * <pre>
 *   SELECT year, month, COUNT(*) AS cnt
 *   FROM   input
 *   GROUP BY 1, 2
 *   ORDER BY 1, 2
 * </pre>
 *
 * <p>Usage — programmatic:
 * <pre>
 *   ParquetSummarizer.run(new ParquetSummarizer.Config(
 *       "database/voucher/voucher_cdr",          // directory → auto-expanded to ** /*.parquet
 *       "SELECT year, month, SUM(amount) AS total FROM input GROUP BY 1, 2",
 *       "reports/voucher_monthly.parquet",
 *       "PARQUET"));
 * </pre>
 *
 * <p>Usage — toon-driven (requires a {@code summarize:} section in the toon map):
 * <pre>
 *   summarize:
 *     input_glob:    database/voucher/voucher_cdr
 *     query:         "SELECT year, month, COUNT(*) AS cnt FROM input GROUP BY 1, 2"
 *     output:        reports/voucher_monthly.parquet
 *     output_format: PARQUET
 * </pre>
 * <pre>
 *   ParquetSummarizer.run(ParquetSummarizer.Config.fromToon(toon));
 * </pre>
 *
 * <p>Both the {@code input_glob} field and the query {@code sql} field can be
 * overridden at runtime by passing a different {@link Config} — the toon acts as
 * a default template, not a hard constraint.
 */
public class ParquetSummarizer {

    // DT_FMT, buildCopyOptions, and deleteQuietly live in DuckDbUtil (shared with PartitionSummarizer)

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration record
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable configuration for a single summarization run.
     *
     * @param inputGlob    Glob pattern or directory path for the source Parquet files.
     *                     If this is a plain directory (no glob metacharacters),
     *                     {@code /**&#47;*.parquet} is appended automatically so that
     *                     Hive-partitioned sub-trees are read recursively.
     * @param sql          SQL query that references the virtual table {@code input}.
     *                     Must be a {@code SELECT} (no trailing semicolon).
     * @param outputPath   Destination file path, e.g. {@code reports/summary.parquet}.
     *                     Parent directories are created if absent.
     * @param outputFormat {@code "PARQUET"} or {@code "CSV"} (case-insensitive;
     *                     defaults to {@code "PARQUET"} when {@code null} or blank).
     */
    public record Config(
            String inputGlob,
            String sql,
            String outputPath,
            String outputFormat
    ) {

        /** Compact constructor — validates and normalises all fields. */
        public Config {
            if (inputGlob == null || inputGlob.isBlank())
                throw new IllegalArgumentException("Config.inputGlob must not be blank");
            sql = SummarizeSupport.validateSelectSql(sql);
            if (outputPath == null || outputPath.isBlank())
                throw new IllegalArgumentException("Config.outputPath must not be blank");
            outputFormat = SummarizeSupport.normalizeOutputFormat(outputFormat);
        }

        /**
         * Load a {@code Config} from the {@code summarize:} section of a pre-parsed
         * toon map.  A runtime {@code sql} override can be applied afterwards by
         * constructing a new record:
         * <pre>
         *   var base = Config.fromToon(toon);
         *   var overridden = new Config(base.inputGlob(), myQuery,
         *                               base.outputPath(), base.outputFormat());
         * </pre>
         *
         * @param toon top-level map produced by {@code JToon.decode(...)}
         * @return a fully-validated {@code Config}
         */
        @SuppressWarnings("unchecked")
        public static Config fromToon(Map<String, Object> toon) {
            Object raw = toon.get("summarize");
            if (!(raw instanceof Map<?, ?> map))
                throw new IllegalArgumentException("Toon must contain a 'summarize:' section");
            Map<String, Object> s = (Map<String, Object>) map;
            return new Config(
                    SummarizeSupport.requiredKey(s, "summarize", "input_glob"),
                    SummarizeSupport.requiredKey(s, "summarize", "query"),
                    SummarizeSupport.requiredKey(s, "summarize", "output"),
                    SummarizeSupport.optionalKey(s, "output_format", "PARQUET")
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute the summarization and write the result to the configured output path.
     *
     * <p>Steps:
     * <ol>
     *   <li>Resolve the input glob (append {@code /**&#47;*.parquet} for plain dirs).</li>
     *   <li>Create a temporary on-disk DuckDB database (avoids native-library state
     *       conflicts; consistent with the rest of the pipeline).</li>
     *   <li>Expose the Parquet dataset as a DuckDB {@code VIEW} named {@code input}.</li>
     *   <li>Materialise the result of {@code config.sql()} into a staging table
     *       ({@code CREATE TABLE summarized AS ...}).</li>
     *   <li>{@code COPY summarized TO <outputPath>} with the requested format.</li>
     *   <li>Delete the temp database files.</li>
     * </ol>
     *
     * @param config summarization parameters
     * @throws Exception on any DuckDB error or I/O failure
     */
    public static void run(Config config) throws Exception {
        long startMs = System.currentTimeMillis();

        log("Starting summarization");
        log("Input  : " + config.inputGlob());
        log("Output : " + config.outputPath() + "  [" + config.outputFormat() + "]");

        // ── resolve glob ──────────────────────────────────────────────────────
        String glob = resolveGlob(config.inputGlob());
        log("Glob   : " + glob);

        // ── ensure output directory exists ────────────────────────────────────
        Path outPath = Paths.get(config.outputPath()).toAbsolutePath().normalize();
        Path outParent = outPath.getParent();
        if (outParent != null) Files.createDirectories(outParent);

        // ── run the shared read_parquet → summarize → COPY kernel ─────────────
        long rowCount = SummarizeSupport.summarizeToFile("duckdb_summary_", glob, config.sql(),
                outPath.toString(), config.outputFormat());

        long elapsed = System.currentTimeMillis() - startMs;
        log(String.format("Done — %,d row(s) → %s  (%,d ms)", rowCount, outPath, elapsed));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve the caller-supplied input to a DuckDB-compatible glob string.
     *
     * <ul>
     *   <li>If the string already contains glob metacharacters ({@code *} or {@code ?})
     *       it is returned as-is after normalising path separators.</li>
     *   <li>If it resolves to an existing directory, {@code /**&#47;*.parquet} is
     *       appended so that the full Hive-partitioned tree is read.</li>
     *   <li>Otherwise it is treated as an explicit file path.</li>
     * </ul>
     */
    private static String resolveGlob(String input) {
        // If the input already contains glob metacharacters, normalise separators
        // and make the base portion (everything before the first '*' or '?') absolute
        // so the path is unambiguous regardless of the JVM working directory.
        if (input.contains("*") || input.contains("?")) {
            int starIdx  = input.indexOf('*');
            int questIdx = input.indexOf('?');
            int metaIdx  = (starIdx < 0) ? questIdx
                         : (questIdx < 0) ? starIdx
                         : Math.min(starIdx, questIdx);
            String base = input.substring(0, metaIdx);
            String tail = input.substring(metaIdx);
            String absBase = Paths.get(base.isEmpty() ? "." : base)
                    .toAbsolutePath().normalize()
                    .toString().replace('\\', '/');
            // Re-attach a trailing slash only when the base string originally ended with
            // a separator (i.e. the glob starts at a directory boundary).
            String sep = (base.endsWith("/") || base.endsWith("\\") || base.isEmpty()) ? "/" : "";
            return absBase + sep + tail.replace('\\', '/');
        }

        Path p = Paths.get(input).toAbsolutePath().normalize();
        if (Files.isDirectory(p))
            return p.toString().replace('\\', '/') + "/**/*.parquet";

        // Plain file path
        return p.toString().replace('\\', '/');
    }

    private static void log(String msg) {
        System.out.printf("[%s] [SUMMARIZE] %s%n",
                LocalDateTime.now().format(DuckDbUtil.DT_FMT), msg);
    }
}
