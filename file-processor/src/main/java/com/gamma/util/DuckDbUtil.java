package com.gamma.util;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared DuckDB JDBC utilities used by {@link ParquetSummarizer},
 * {@link PartitionSummarizer}, and {@link com.gamma.inspector.SourceProcessor}.
 *
 * <p>Central home for every recurring DuckDB boilerplate pattern:
 * <ul>
 *   <li>Explicit JDBC driver registration ({@link #loadDriver()})</li>
 *   <li>Temp-file database creation ({@link #tempDbFile(String)})</li>
 *   <li>JDBC URL construction ({@link #jdbcUrl(File)})</li>
 *   <li>Two-file cleanup — {@code .db} + {@code .wal} ({@link #deleteTempDb(File)})</li>
 *   <li>{@code COPY … TO} format clauses ({@link #buildCopyOptions(String)})</li>
 * </ul>
 */
public final class DuckDbUtil {

    /** Timestamp pattern shared by all pipeline log messages. */
    public static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DuckDbUtil() {
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Driver + connection helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Explicitly registers the DuckDB JDBC driver via {@code Class.forName}.
     *
     * <p>Required when the {@code META-INF/services} ServiceLoader entry is stripped
     * or merged incorrectly during fat-JAR shading (observed with maven-shade-plugin).
     * Safe to call multiple times — the driver loader is idempotent.
     *
     * @throws ClassNotFoundException if the DuckDB driver JAR is absent from the classpath
     */
    public static void loadDriver() throws ClassNotFoundException {
        Class.forName("org.duckdb.DuckDBDriver");
    }

    /**
     * Creates and immediately pre-deletes a temporary file for use as a DuckDB database.
     *
     * <p>DuckDB creates a fresh, empty database when given a path that does not yet exist,
     * so pre-deleting the temp file is the correct way to obtain a clean, uniquely-named
     * on-disk database without leaving a zero-byte placeholder.
     *
     * @param prefix temp-file name prefix (e.g. {@code "duckdb_worker_"})
     * @return the (now-deleted) {@link File} whose path DuckDB will use
     * @throws IOException if the temp file cannot be created
     */
    public static File tempDbFile(String prefix) throws IOException {
        File f = File.createTempFile(prefix, ".db");
        f.delete();
        return f;
    }

    /**
     * Opens a DuckDB JDBC connection to {@code dbFile}.
     *
     * <p>Convenience wrapper that combines {@link #jdbcUrl(File)} with
     * {@link DriverManager#getConnection(String)}.  The caller is responsible for
     * closing the returned connection (use try-with-resources).
     *
     * @param dbFile file returned by {@link #tempDbFile(String)}
     * @return an open {@link Connection}
     * @throws SQLException if DuckDB cannot open or create the database
     */
    public static Connection openConnection(File dbFile) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(dbFile));
    }

    /**
     * Returns the JDBC URL for a DuckDB database file.
     *
     * <p>Forward slashes are used unconditionally: DuckDB's URL parser rejects
     * Windows backslashes in the {@code jdbc:duckdb:} scheme.
     *
     * @param dbFile the database file (may or may not exist yet)
     * @return a {@code jdbc:duckdb:<path>} URL ready to pass to {@link DriverManager}
     */
    public static String jdbcUrl(File dbFile) {
        return "jdbc:duckdb:" + dbFile.getAbsolutePath().replace('\\', '/');
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cleanup helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Deletes a DuckDB database file <em>and</em> its companion WAL file
     * ({@code <path>.wal}).
     *
     * <p>Both deletions go through {@link #deleteQuietly(File)} so failures are
     * logged rather than thrown.  Safe to call even when the files no longer exist.
     *
     * @param dbFile the {@code .db} file to remove (the {@code .wal} path is derived
     *               by appending {@code ".wal"} to the absolute path)
     */
    public static void deleteTempDb(File dbFile) {
        deleteQuietly(dbFile);
        deleteQuietly(new File(dbFile.getAbsolutePath() + ".wal"));
    }

    /**
     * Deletes {@code f}, printing a {@code [CLEANUP]} warning if the file exists
     * but the deletion fails.  {@link File#delete()} never throws — failures are
     * surfaced only via the return value, which this method checks.
     *
     * @param f the file to delete (no-op when {@code f} does not exist)
     */
    public static void deleteQuietly(File f) {
        if (!f.delete() && f.exists())
            System.out.printf("[%s] [CLEANUP] WARN: could not delete temp file: %s%n",
                    LocalDateTime.now().format(DT_FMT), f.getAbsolutePath());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COPY-TO format helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds the {@code COPY … TO} options clause for the requested format.
     *
     * <ul>
     *   <li>{@code "PARQUET"} → {@code FORMAT PARQUET, COMPRESSION SNAPPY}</li>
     *   <li>{@code "CSV"}     → {@code FORMAT CSV, HEADER true}</li>
     * </ul>
     *
     * @param format {@code "PARQUET"} or {@code "CSV"} (must already be upper-cased)
     * @return the options string to embed in a {@code COPY … TO '<path>' (<options>)} statement
     * @throws IllegalStateException for any unrecognised format string
     */
    public static String buildCopyOptions(String format) {
        return switch (format) {
            case "PARQUET" -> "FORMAT PARQUET, COMPRESSION SNAPPY";
            case "CSV"     -> "FORMAT CSV, HEADER true";
            default        -> throw new IllegalStateException("Unexpected format: " + format);
        };
    }
}
