package com.gamma.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Traverses a Hive-partitioned Parquet directory tree and produces one summary
 * output file per leaf partition in a parallel output tree of identical structure.
 *
 * <p>Runs incrementally: any partition whose summary file already exists in the
 * output tree is skipped without re-reading the source data.
 *
 * <p>Input tree (source):
 * <pre>
 *   database/voucher/
 *     year=2000/month=01/day=01/  ← adj_DATE_20200101_out.parquet
 *                                    adj_DATE_20200403_out.parquet
 *     year=1900/month=01/day=01/  ← adj_DATE_20200116_out.parquet  (sentinel)
 * </pre>
 *
 * <p>Output tree (summaries):
 * <pre>
 *   database/voucher_summary/
 *     year=2000/month=01/day=01/_summary.parquet
 *     year=1900/month=01/day=01/_summary.parquet
 * </pre>
 *
 * <p>Usage — programmatic:
 * <pre>
 *   PartitionSummarizer.run(new PartitionSummarizer.Config(
 *       "database/voucher",
 *       "database/voucher_summary",
 *       "SELECT COUNT(*) AS cnt, SUM(amount) AS total FROM input",
 *       "PARQUET", "_summary", 4));
 * </pre>
 *
 * <p>Usage — toon-driven (requires a {@code partition_summarize:} section):
 * <pre>
 *   partition_summarize:
 *     input_root:     database/voucher
 *     output_root:    database/voucher_summary
 *     query:          "SELECT year, month, day, COUNT(*) AS cnt FROM input GROUP BY 1, 2, 3"
 *     output_format:  PARQUET
 *     output_file:    _summary
 *     threads:        4
 * </pre>
 * <pre>
 *   PartitionSummarizer.run(PartitionSummarizer.Config.fromToon(toon));
 * </pre>
 *
 * <p>The SQL query references the virtual table {@code input}, which resolves to
 * all {@code .parquet} files found directly inside each leaf-partition directory.
 */
public class PartitionSummarizer {

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration record
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable configuration for a partition-summarize run.
     *
     * @param inputRoot    Root of the Hive-partitioned source tree.
     * @param outputRoot   Root of the parallel output tree.
     *                     Must not be the same as — or a subdirectory of — {@code inputRoot}.
     * @param sql          {@code SELECT} statement referencing virtual table {@code input}
     *                     (no trailing semicolon). Same contract as
     *                     {@link ParquetSummarizer.Config#sql()}.
     * @param outputFormat {@code "PARQUET"} or {@code "CSV"} (defaults to {@code "PARQUET"}).
     * @param outputFile   Base name for the per-partition summary file, without extension
     *                     (defaults to {@code "_summary"}).
     * @param threads      Number of partitions processed concurrently.
     *                     {@code ≤ 0} → use all available processors.
     */
    public record Config(
            String inputRoot,
            String outputRoot,
            String sql,
            String outputFormat,
            String outputFile,
            int    threads
    ) {

        /** Compact constructor — validates and normalises all fields. */
        public Config {
            if (inputRoot == null || inputRoot.isBlank())
                throw new IllegalArgumentException("Config.inputRoot must not be blank");
            if (outputRoot == null || outputRoot.isBlank())
                throw new IllegalArgumentException("Config.outputRoot must not be blank");

            // Guard: outputRoot must not equal or be a subdirectory of inputRoot.
            // If they overlap, _summary.parquet files land in the source tree and are
            // picked up as source data on the next run, corrupting all future summaries.
            Path inAbs  = Paths.get(inputRoot).toAbsolutePath().normalize();
            Path outAbs = Paths.get(outputRoot).toAbsolutePath().normalize();
            if (outAbs.startsWith(inAbs))
                throw new IllegalArgumentException(
                        "Config.outputRoot must not be the same as or under Config.inputRoot "
                        + "(inputRoot=" + inAbs + ", outputRoot=" + outAbs + ")");

            sql = SummarizeSupport.validateSelectSql(sql);
            outputFormat = SummarizeSupport.normalizeOutputFormat(outputFormat);
            outputFile = (outputFile == null || outputFile.isBlank()) ? "_summary" : outputFile.trim();
            threads = threads <= 0 ? Runtime.getRuntime().availableProcessors() : threads;
        }

        /**
         * Load a {@code Config} from the {@code partition_summarize:} section of a
         * pre-parsed toon map.
         *
         * @param toon top-level map produced by {@code JToon.decode(...)}
         * @return a fully-validated {@code Config}
         */
        @SuppressWarnings("unchecked")
        public static Config fromToon(Map<String, Object> toon) {
            Object raw = toon.get("partition_summarize");
            if (!(raw instanceof Map<?, ?> map))
                throw new IllegalArgumentException(
                        "Toon must contain a 'partition_summarize:' section");
            Map<String, Object> s = (Map<String, Object>) map;
            int t = 0;
            try { t = Integer.parseInt(String.valueOf(s.getOrDefault("threads", "0"))); }
            catch (NumberFormatException ignored) { /* fall back to default */ }
            return new Config(
                    SummarizeSupport.requiredKey(s, "partition_summarize", "input_root"),
                    SummarizeSupport.requiredKey(s, "partition_summarize", "output_root"),
                    SummarizeSupport.requiredKey(s, "partition_summarize", "query"),
                    SummarizeSupport.optionalKey(s, "output_format", "PARQUET"),
                    SummarizeSupport.optionalKey(s, "output_file",   "_summary"),
                    t
            );
        }

        /** Full filename for the summary output, e.g. {@code _summary.parquet}. */
        public String outputFileName() {
            return outputFile + ("CSV".equals(outputFormat) ? ".csv" : ".parquet");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Traverse all leaf partitions under {@code config.inputRoot()} and write one
     * summary file per partition into the parallel output tree rooted at
     * {@code config.outputRoot()}.
     *
     * <p>Partitions whose summary file already exists in the output tree are skipped
     * (incremental mode). All other partitions are processed concurrently using a
     * fixed thread pool bounded by {@code config.threads()}.
     *
     * <p>Per-partition errors are logged and counted but do not abort the run.
     * The final line reports processed / skipped / failed counts.
     *
     * @param config summarization parameters
     * @throws Exception if the input tree cannot be walked, or if a thread is interrupted
     */
    public static void run(Config config) throws Exception {
        long startMs = System.currentTimeMillis();

        Path inputRoot  = Paths.get(config.inputRoot()).toAbsolutePath().normalize();
        Path outputRoot = Paths.get(config.outputRoot()).toAbsolutePath().normalize();

        log("Starting partition summarization");
        log("Input  : " + inputRoot);
        log("Output : " + outputRoot);
        log("Format : " + config.outputFormat() + "  file: " + config.outputFileName());
        log("Threads: " + config.threads());

        // ── 1. discover all leaf partitions ───────────────────────────────────
        List<Path> partitions = findLeafPartitions(inputRoot);
        log(String.format("Found %d leaf partition(s)", partitions.size()));
        if (partitions.isEmpty()) return;

        // ── 2. process in parallel via fixed thread pool ──────────────────────
        // Matches the SourceProcessor concurrency pattern: a bounded pool queues
        // all submitted tasks internally; no semaphore or Future list needed.
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger skipped   = new AtomicInteger();
        AtomicInteger failed    = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(config.threads())) {
            for (Path partition : partitions) {
                pool.submit(() -> {
                    try {
                        boolean did = summarizePartition(partition, inputRoot, outputRoot, config);
                        (did ? processed : skipped).incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        log("ERROR [" + inputRoot.relativize(partition) + "]: " + e.getMessage());
                    }
                });
            }
        } // ExecutorService.close() = shutdown() + awaitTermination(forever)

        long elapsed = System.currentTimeMillis() - startMs;
        log(String.format("Complete — %d processed, %d skipped, %d failed  (%,d ms)",
                processed.get(), skipped.get(), failed.get(), elapsed));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-partition logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Summarize a single leaf partition.
     *
     * @param partition  absolute path to the leaf directory containing {@code .parquet} files
     * @param inputRoot  absolute root of the source tree (used to compute the relative path)
     * @param outputRoot absolute root of the output tree
     * @param config     run configuration
     * @return {@code true} if the partition was summarized, {@code false} if it was skipped
     */
    private static boolean summarizePartition(Path partition,
                                              Path inputRoot,
                                              Path outputRoot,
                                              Config config) throws Exception {
        Path relPath = inputRoot.relativize(partition);
        Path outDir  = outputRoot.resolve(relPath);
        Path outFile = outDir.resolve(config.outputFileName());

        // ── incremental check ─────────────────────────────────────────────────
        if (Files.exists(outFile)) {
            log("SKIP  [" + relPath + "]");
            return false;
        }

        log("START [" + relPath + "]");
        Files.createDirectories(outDir);

        // ── glob for all .parquet files in this partition dir ─────────────────
        String glob = partition.toString().replace('\\', '/') + "/*.parquet";

        long rowCount = SummarizeSupport.summarizeToFile("duckdb_psumm_", glob, config.sql(),
                outFile.toString(), config.outputFormat());
        log(String.format("DONE  [%s] — %,d row(s)", relPath, rowCount));
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tree walker
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Walk the Hive directory tree and collect every leaf-partition directory —
     * i.e. any directory that contains at least one {@code .parquet} file directly
     * (not recursively).
     *
     * <p>Hidden directories (names starting with {@code .}) are skipped entirely
     * to exclude DuckDB staging dirs, {@code .git}, etc.
     *
     * <p>The returned list is sorted by natural path order for deterministic
     * processing across platforms.
     *
     * @param root root of the Hive-partitioned tree
     * @return sorted list of absolute paths to leaf partition directories
     */
    private static List<Path> findLeafPartitions(Path root) throws IOException {
        Set<Path> seen = new LinkedHashSet<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                // Skip hidden dirs (.staging, .git, etc.) but always enter root
                return (!dir.equals(root) && name.startsWith("."))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".parquet"))
                    seen.add(file.getParent());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                log("WARN: cannot access " + file + " — " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        List<Path> result = new ArrayList<>(seen);
        result.sort(Comparator.naturalOrder()); // deterministic order across platforms
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void log(String msg) {
        System.out.printf("[%s] [PART-SUMMARIZE] %s%n",
                LocalDateTime.now().format(DuckDbUtil.DT_FMT), msg);
    }
}
