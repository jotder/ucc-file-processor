package com.gamma.sql;

import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A hardened, ephemeral DuckDB connection used to <em>validate</em> agent-generated SQL — the
 * security boundary for {@code kpi-to-sql} (M6 / v3.6.0; closes architecture gap G4). DuckDB is used
 * out-of-the-box elsewhere; an LLM-emitted query left unchecked could read arbitrary files
 * ({@code read_csv('/etc/…')}), write outside the workspace ({@code COPY … TO}), or pull/execute
 * extensions ({@code INSTALL}/{@code LOAD}/{@code ATTACH}). This connection is one of two layers that
 * stop that (the other is the lexical {@link SqlGuard}); neither alone is sufficient.
 *
 * <h3>Two phases — why {@code open} then {@code seal}</h3>
 * The oracle must read the <em>legitimate</em> partition files the query targets, and
 * {@code enable_external_access=false} blocks <em>all</em> file access (the legitimate reads too). So
 * the sandbox runs in two phases:
 * <ol>
 *   <li><b>{@link #open}</b> — opens a fresh temp DB with extension auto-install/auto-load disabled and
 *       memory/thread caps applied, but file access still on. The <em>trusted</em> caller (the oracle)
 *       registers its views/tables over the known-safe partition paths here.</li>
 *   <li><b>{@link #seal}</b> — flips {@code enable_external_access=false} and {@code lock_configuration
 *       =true}. After this, the <em>untrusted</em> candidate SQL runs with no file access and a frozen
 *       configuration it cannot re-open (a guard bypass still cannot reach the filesystem).</li>
 * </ol>
 *
 * <p>{@link AutoCloseable}: {@link #close()} closes the connection and removes the temp DB (+ its WAL),
 * mirroring the {@link DuckDbUtil#deleteTempDb(File)} cleanup pattern used across the engine.
 *
 * @since 3.6.0
 */
public final class SqlSandbox implements AutoCloseable {

    private final File dbFile;
    private final Connection conn;
    private final int queryTimeoutSeconds;
    private boolean sealed;

    private SqlSandbox(File dbFile, Connection conn, int queryTimeoutSeconds) {
        this.dbFile = dbFile;
        this.conn = conn;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * Open a hardened sandbox connection under {@code policy}. Extension auto-install/auto-load are
     * disabled and memory/thread caps applied immediately; file access stays on until {@link #seal()}
     * so the trusted caller can register views over real partitions.
     *
     * @throws SQLException if DuckDB cannot open the temp DB or apply the hardening pragmas
     * @throws IOException  if the temp DB file cannot be created
     */
    public static SqlSandbox open(SqlSandboxPolicy policy) throws SQLException, IOException {
        SqlSandboxPolicy p = (policy == null) ? SqlSandboxPolicy.defaultPolicy() : policy;
        try {
            DuckDbUtil.loadDriver();
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB JDBC driver not on the classpath", e);
        }
        File db = DuckDbUtil.tempDbFile("sql_sandbox_");
        Connection conn = DuckDbUtil.openConnection(db);
        try (Statement st = conn.createStatement()) {
            // Block extension escalation up front; these are immutable once configuration is locked.
            st.execute("SET autoinstall_known_extensions=false");
            st.execute("SET autoload_known_extensions=false");
            st.execute("SET memory_limit='" + sanitizeMemory(p.memoryLimit()) + "'");
            st.execute("SET threads=" + p.maxThreads());
        } catch (SQLException e) {
            closeQuietly(conn, db);
            throw e;
        }
        return new SqlSandbox(db, conn, p.queryTimeoutSeconds());
    }

    /**
     * Seal the connection before running untrusted SQL: deny all external (file/network) access and
     * lock the configuration so it cannot be re-opened. Idempotent. Call <em>after</em> the trusted
     * view/table registration and <em>before</em> the candidate query.
     */
    public void seal() throws SQLException {
        if (sealed) return;
        try (Statement st = conn.createStatement()) {
            st.execute("SET enable_external_access=false");
            st.execute("SET lock_configuration=true");
        }
        sealed = true;
    }

    /** The underlying connection. Caller must not close it directly — use {@link #close()}. */
    public Connection connection() {
        return conn;
    }

    /** A statement with the policy's query timeout applied (best-effort; ignored if unsupported). */
    public Statement statement() throws SQLException {
        Statement st = conn.createStatement();
        try {
            st.setQueryTimeout(queryTimeoutSeconds);
        } catch (SQLException | RuntimeException ignored) {
            // DuckDB's driver may not support query timeout; the memory cap is the hard backstop.
        }
        return st;
    }

    /** Whether {@link #seal()} has been applied. */
    public boolean isSealed() {
        return sealed;
    }

    @Override
    public void close() {
        closeQuietly(conn, dbFile);
    }

    private static void closeQuietly(Connection conn, File db) {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
            // best-effort
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Keep the memory_limit literal free of quote-injection (it is interpolated into a SET). */
    private static String sanitizeMemory(String memoryLimit) {
        return memoryLimit.replace("'", "").replace(";", "");
    }
}
