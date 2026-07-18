package com.gamma.sql;

/**
 * Builds DuckDB table-function expressions that read a Parquet/CSV dataset — the view-registration
 * idiom shared by the Stage-2 {@code EnrichmentEngine} (the production path) and the M6
 * {@link SqlOracle} (the {@code kpi-to-sql} validation path). Extracted at v3.6.0 so both callers
 * register inputs identically; there is exactly one place that decides how a partitioned dataset is
 * read, so the oracle validates against the same shape production runs against.
 *
 * <p>{@code hive_types_autocast=0} keeps Hive partition values as {@code VARCHAR} so zero-padded
 * month/day segments (e.g. {@code "04"}) survive rather than being coerced to integers.
 *
 * @since 3.6.0
 */
public final class SqlViews {

    private SqlViews() {}

    /** The file extension for a format: {@code "parquet"} or (default) {@code "csv"}. */
    public static String ext(String format) {
        return "PARQUET".equals(format) ? "parquet" : "csv";
    }

    /**
     * The canonical read root of a store directory: its {@code database/} subtree when one exists —
     * a pipeline-shaped store reads its <em>mapped output</em>, so the sibling {@code backup/},
     * {@code quarantine/} and any nested trees never leak into recursive reads — else the directory
     * itself (a flat snapshot store). The read-side half of the store-layout contract
     * (BACKLOG §1, decided 2026-07-18); the write-side half is {@code PipelineJobRunner}'s
     * top-level-sink rule.
     *
     * @param storeDir the store directory (either slash style)
     * @return the directory to glob under, forward-slashed
     */
    public static String storeReadRoot(String storeDir) {
        String dir = storeDir.replace("\\", "/");
        return java.nio.file.Files.isDirectory(java.nio.file.Path.of(dir, "database"))
                ? dir + "/database" : dir;
    }

    /**
     * A DuckDB table-function reading {@code pathOrGlob} in the given {@code format}.
     *
     * @param format     {@code "PARQUET"} or {@code "CSV"}
     * @param pathOrGlob the file or glob to read (back-slashes are normalised to forward slashes)
     * @param hive       when {@code true}, enable {@code hive_partitioning} (with VARCHAR partition
     *                   values via {@code hive_types_autocast=0})
     * @return a {@code read_parquet(...)} / {@code read_csv(...)} expression
     * @throws IllegalArgumentException for an unsupported format
     */
    public static String reader(String format, String pathOrGlob, boolean hive) {
        String p = pathOrGlob.replace("\\", "/");
        return switch (format) {
            // union_by_name: a store's files may gain additive columns over time (e.g. a Decision
            // Rule's tag consequence adds __tags from some batch onward) — align by name, not position.
            case "PARQUET" -> "read_parquet('" + p + "', union_by_name=true"
                    + (hive ? ", hive_partitioning=true, hive_types_autocast=0" : "") + ")";
            case "CSV" -> "read_csv('" + p + "', header=true, all_varchar=true, union_by_name=true"
                    + (hive ? ", hive_partitioning=true, hive_types_autocast=0" : "") + ")";
            default -> throw new IllegalArgumentException("Unsupported format: " + format);
        };
    }
}
