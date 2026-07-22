package com.gamma.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Shared DuckDB JDBC utilities used by {@link ParquetSummarizer},
 * {@link PartitionSummarizer}, and {@code com.gamma.inspector.CollectorProcessor} (core).
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
     * Like {@link #tempDbFile(String)} but creates the temp database in {@code dir} instead of the
     * JVM's {@code java.io.tmpdir} (typically the system {@code /tmp}).
     *
     * <p>This matters for large inputs: both the on-disk temp database <em>and</em> DuckDB's spill
     * scratch ({@code <dbfile>.tmp}) live next to this file, so pointing it at a roomy data volume
     * (e.g. the pipeline's {@code dirs.temp}) keeps multi-hundred-GB scratch off a small
     * {@code /tmp}. The directory is created if absent.
     *
     * @param prefix temp-file name prefix (e.g. {@code "duckdb_batch_"})
     * @param dir    directory to create the temp database in (must be writable / creatable)
     * @return the (now-deleted) {@link File} whose path DuckDB will use
     * @throws IOException if the directory or temp file cannot be created
     */
    public static File tempDbFile(String prefix, Path dir) throws IOException {
        Files.createDirectories(dir);
        File f = File.createTempFile(prefix, ".db", dir.toFile());
        f.delete();
        return f;
    }

    /**
     * Apply optional DuckDB resource controls to a worker connection via {@code SET} statements.
     * Each argument is applied only when non-null/non-blank; all-null is a no-op leaving DuckDB's
     * own defaults. Kept dependency-free (primitive args) so {@code com.gamma.util} need not depend
     * on the config model.
     *
     * <ul>
     *   <li>{@code temp_directory} — where DuckDB spills; aim at a roomy data volume, not /tmp.</li>
     *   <li>{@code memory_limit} — RAM cap (DuckDB size string, e.g. {@code "16GB"}).</li>
     *   <li>{@code max_temp_directory_size} — spill cap so a runaway query fails fast.</li>
     * </ul>
     *
     * @param conn                 an open DuckDB connection
     * @param memoryLimit          DuckDB {@code memory_limit} value, or {@code null} to leave default
     * @param tempDirectory        DuckDB {@code temp_directory} value, or {@code null} to leave default
     * @param maxTempDirectorySize DuckDB {@code max_temp_directory_size} value, or {@code null} to leave default
     */
    public static void applyDuckDbSettings(Connection conn, String memoryLimit,
                                           String tempDirectory, String maxTempDirectorySize)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (notBlank(tempDirectory))
                st.execute("SET temp_directory='" + sqlLiteral(tempDirectory.replace('\\', '/')) + "'");
            if (notBlank(memoryLimit))
                st.execute("SET memory_limit='" + sqlLiteral(memoryLimit) + "'");
            if (notBlank(maxTempDirectorySize))
                st.execute("SET max_temp_directory_size='" + sqlLiteral(maxTempDirectorySize) + "'");
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Escape single quotes for a single-quoted SQL string literal. */
    private static String sqlLiteral(String s) { return s.replace("'", "''"); }

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
     * Cap a worker connection's internal DuckDB parallelism via {@code PRAGMA threads=N}.
     *
     * <p>DuckDB defaults to one thread per core. When several batches run concurrently
     * (each with its own connection), the product of batch-concurrency × per-connection
     * threads can oversubscribe the CPU and add I/O contention. Setting this makes the
     * pipeline's controllable thread count honest.
     *
     * <p>No-op when {@code threads <= 0} (leave DuckDB's default).
     *
     * @param conn    an open DuckDB connection
     * @param threads desired per-connection thread count; {@code <= 0} leaves the default
     */
    public static void applyWorkerThreads(Connection conn, int threads) throws SQLException {
        if (threads <= 0) return;
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA threads=" + threads);
        }
    }

    // ── global (JVM-property) fallback caps ──────────────────────────────────────────────────

    /** JVM-property names for the global DuckDB caps — mirror the {@code processing.duckdb.*} config keys. */
    public static final String PROP_MEMORY_LIMIT = "processing.duckdb.memory_limit";
    public static final String PROP_TEMP_DIRECTORY = "processing.duckdb.temp_directory";
    public static final String PROP_MAX_TEMP_DIRECTORY_SIZE = "processing.duckdb.max_temp_directory_size";
    public static final String PROP_THREADS = "processing.duckdb.threads";

    /**
     * The global fallback for a DuckDB setting: the per-config {@code configured} value wins; when it is
     * blank, the {@code -D<property>} value applies; when neither is set, {@code null} ⇒
     * {@link #applyDuckDbSettings} leaves DuckDB's own default. Lets a single {@code -Dprocessing.duckdb.*}
     * flag cap every scratch connection uniformly, with no behaviour change when the flag is unset.
     */
    public static String globalOr(String configured, String property) {
        if (notBlank(configured)) return configured;
        String p = System.getProperty(property);
        return notBlank(p) ? p : null;
    }

    /**
     * Apply the global {@code -Dprocessing.duckdb.*} caps (memory_limit, spill {@code temp_directory},
     * spill-size cap, and worker {@code threads}) to a scratch connection that has no per-pipeline
     * {@code processing.duckdb} config to read from — the flow-job ({@code PipelineJobRunner}) and
     * enrichment ({@code EnrichmentEngine}) scratch DBs, which would otherwise open fully uncapped while
     * the batch-ingest path caps its own connections. Every property is opt-in: unset ⇒ no {@code SET}/
     * {@code PRAGMA} is issued ⇒ DuckDB keeps its own defaults, so behaviour is unchanged unless an
     * operator sets the flags (one knob then caps all three paths).
     */
    public static void applyGlobalDuckDbSettings(Connection conn) throws SQLException {
        applyDuckDbSettings(conn,
                System.getProperty(PROP_MEMORY_LIMIT),
                System.getProperty(PROP_TEMP_DIRECTORY),
                System.getProperty(PROP_MAX_TEMP_DIRECTORY_SIZE));
        applyWorkerThreads(conn, Integer.getInteger(PROP_THREADS, 0));
    }

    /**
     * Resolve the effective per-connection DuckDB thread count for a batch worker, given the
     * configured value, the batch concurrency, and the machine's core count.
     *
     * <p>This is the anti-oversubscription policy. DuckDB defaults to one thread per core, so when
     * {@code batchConcurrency} batches each open their own connection, the product
     * {@code batchConcurrency × cores} of DuckDB workers fights over {@code cores} CPUs — the
     * kernel-time blowup (futex / TLB / mmap-lock contention) that looks like ~100% sys, ~2% user.
     *
     * <ul>
     *   <li>{@code configured > 0} — honor it exactly (explicit tuning; unchanged behaviour).</li>
     *   <li>{@code configured == 0} (the default) — auto-derive: with more than one concurrent batch,
     *       split the cores evenly ({@code max(1, cores / batchConcurrency)}) so
     *       {@code batchConcurrency × result ≈ cores}; with a single batch, return {@code 0} (let
     *       DuckDB use every core — no oversubscription is possible).</li>
     *   <li>{@code configured < 0} — explicit opt-out: return {@code 0} so DuckDB keeps its own
     *       per-core default even under concurrency (use when you deliberately want one batch to
     *       grab the whole machine).</li>
     * </ul>
     *
     * <p>A {@code 0} result is the "leave DuckDB's default" sentinel understood by
     * {@link #applyWorkerThreads}. Kept dependency-free (primitive args) so {@code com.gamma.util}
     * need not depend on the config model, and pure so it is trivially unit-testable.
     *
     * @param configured       the configured {@code processing.duckdb_threads}
     * @param batchConcurrency  the configured {@code processing.threads} (concurrent batches)
     * @param availableCores    {@link Runtime#availableProcessors()} on this host
     * @return the value to pass to {@link #applyWorkerThreads} ({@code 0} = leave DuckDB default)
     */
    public static int effectiveWorkerThreads(int configured, int batchConcurrency, int availableCores) {
        if (configured > 0) return configured;             // explicit cap — honor exactly
        if (configured < 0) return 0;                      // explicit opt-out — DuckDB per-core default
        if (batchConcurrency <= 1) return 0;               // single batch — all cores, can't oversubscribe
        return Math.max(1, availableCores / batchConcurrency);   // auto: divide cores among batches
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
