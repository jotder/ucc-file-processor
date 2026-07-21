package com.gamma.util;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

/**
 * Shared internals for {@link ParquetSummarizer} and {@link PartitionSummarizer} — the parts that
 * were duplicated verbatim between their {@code Config} records and {@code run} paths: SELECT/format
 * validation, the {@code summarize:} toon-key helpers, and the DuckDB
 * {@code read_parquet → CREATE TABLE summarized → COPY} kernel. Package-private; the two summarizers
 * keep their own discovery / logging / incremental-skip behaviour and only delegate the common core.
 */
final class SummarizeSupport {

    private SummarizeSupport() {}

    /**
     * Validate + normalise a summarize SELECT: trims leading/trailing whitespace, requires a leading
     * {@code SELECT}, and rejects an embedded {@code ;} (guards against multi-statement injection or a
     * non-SELECT payload). Returns the trimmed SQL.
     */
    static String validateSelectSql(String sql) {
        if (sql == null || sql.isBlank())
            throw new IllegalArgumentException("Config.sql must not be blank");
        String sqlTrimmed = sql.trim();
        if (!sqlTrimmed.toUpperCase().startsWith("SELECT"))
            throw new IllegalArgumentException(
                    "Config.sql must be a SELECT statement, got: "
                    + sqlTrimmed.substring(0, Math.min(40, sqlTrimmed.length())) + "...");
        if (sqlTrimmed.contains(";"))
            throw new IllegalArgumentException("Config.sql must not contain a semicolon");
        return sqlTrimmed;
    }

    /** Normalise an output format to {@code PARQUET} (the default) or {@code CSV}, else throw. */
    static String normalizeOutputFormat(String outputFormat) {
        String fmt = (outputFormat == null || outputFormat.isBlank())
                ? "PARQUET" : outputFormat.trim().toUpperCase();
        if (!fmt.equals("PARQUET") && !fmt.equals("CSV"))
            throw new IllegalArgumentException(
                    "Config.outputFormat must be PARQUET or CSV, got: " + fmt);
        return fmt;
    }

    /** A required toon key under {@code section} (named in the error message), trimmed. */
    static String requiredKey(Map<String, Object> m, String section, String key) {
        Object v = m.get(key);
        if (v == null || v.toString().isBlank())
            throw new IllegalArgumentException(section + "." + key + " is required");
        return v.toString().trim();
    }

    /** An optional toon key, trimmed, or {@code def} when absent/blank. */
    static String optionalKey(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString().trim() : def;
    }

    /**
     * The shared DuckDB summarize kernel: open a temp-file DuckDB (prefix {@code tempPrefix}), expose
     * {@code inputGlob} as the {@code input} view via {@code read_parquet(..., union_by_name=true)}
     * (so multi-file reads with varying precision widen rather than fail), materialise {@code sql} into
     * a {@code summarized} table (the two-step CTAS avoids a DuckDB AVX2 crash), {@code COPY} it to
     * {@code outputPath} in {@code outputFormat}, and return the summarized row count. The temp DB is
     * always deleted.
     */
    static long summarizeToFile(String tempPrefix, String inputGlob, String sql,
                                String outputPath, String outputFormat) throws Exception {
        File tempDb = DuckDbUtil.tempDbFile(tempPrefix);
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection(DuckDbUtil.jdbcUrl(tempDb));
             Statement stmt = conn.createStatement()) {
            // Single quotes inside the glob path are escaped by doubling them.
            stmt.execute(String.format(
                    "CREATE VIEW input AS SELECT * FROM read_parquet('%s', union_by_name=true)",
                    inputGlob.replace("'", "''")));
            stmt.execute("CREATE TABLE summarized AS\n" + sql);
            long rowCount;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM summarized")) {
                rs.next();
                rowCount = rs.getLong(1);
            }
            stmt.execute(String.format("COPY summarized TO '%s' (%s)",
                    outputPath.replace('\\', '/'), DuckDbUtil.buildCopyOptions(outputFormat)));
            return rowCount;
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }
}
