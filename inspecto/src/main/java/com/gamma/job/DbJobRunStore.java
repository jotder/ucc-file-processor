package com.gamma.job;

import com.gamma.api.PublicApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database-backed projection of {@link JobRun}s into a DuckDB table for job-execution reporting (T27,
 * §3.8). Mirrors {@link com.gamma.service.DbStatusStore}: plain JDBC over the bundled DuckDB engine
 * (no new dependency), a single shared {@link Connection} (job traffic is low-volume and a JDBC
 * connection is not thread-safe), schema created on open.
 *
 * <p>The durable {@code jobs_runs.csv} audit remains the write-time record; this store is the
 * <em>queryable</em> projection {@link JobService} writes through to when a backend is configured
 * ({@code -Djobs.backend=duckdb}). It answers the analytical questions the CSV can't cheaply serve —
 * success rate, p50/p95 duration, and failure trend over time — and backs the Jobs reporting pane.
 *
 * <p>Default-off: when no backend is configured the store is absent and the {@code /jobs/metrics}
 * endpoints 404; nothing about the existing in-memory history / CSV audit changes.
 */
@PublicApi(since = "4.3.0")
public final class DbJobRunStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbJobRunStore.class);
    private static final String T_RUNS = "inspecto_job_runs";

    private final Connection conn;

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbJobRunStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /**
     * Open a job-run DB by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:jobs.db}; a self-registering
     * driver otherwise). Postgres is supported by the same code if its driver is on the classpath.
     */
    public static DbJobRunStore open(String url) throws SQLException {
        try {
            if (url.startsWith("jdbc:duckdb:")) Class.forName("org.duckdb.DuckDBDriver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No JDBC driver on the classpath for " + url, e);
        }
        return new DbJobRunStore(DriverManager.getConnection(url));
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + T_RUNS + " ("
                    + "run_id VARCHAR, job VARCHAR, type VARCHAR, \"trigger\" VARCHAR, "
                    + "start_time VARCHAR, end_time VARCHAR, status VARCHAR, "
                    + "duration_ms BIGINT, message VARCHAR)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise job-run DB schema", e);
        }
    }

    /** Append one job run. Best-effort: a write failure is logged, never thrown (the CSV audit is the record). */
    public synchronized void record(JobRun r) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + T_RUNS
                + " (run_id, job, type, \"trigger\", start_time, end_time, status, duration_ms, message)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.runId());
            ps.setString(2, r.job());
            ps.setString(3, r.type());
            ps.setString(4, r.trigger());
            ps.setString(5, r.startTime());
            ps.setString(6, r.endTime());
            ps.setString(7, r.status());
            ps.setLong(8, r.durationMs());
            ps.setString(9, r.message());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not project job run {} to the job-run DB: {}", r.runId(), e.getMessage());
        }
    }

    /**
     * Aggregate execution metrics, optionally for one {@code job} (null/blank = all jobs): total runs,
     * success / failed counts, success rate (0..1), and p50 / p95 / mean duration in ms.
     */
    public synchronized Map<String, Object> metrics(String job) {
        boolean filtered = job != null && !job.isBlank();
        String sql = "SELECT count(*) total,"
                + " count(*) FILTER (WHERE status='SUCCESS') success,"
                + " count(*) FILTER (WHERE status<>'SUCCESS') failed,"
                + " quantile_cont(duration_ms, 0.5) p50,"
                + " quantile_cont(duration_ms, 0.95) p95,"
                + " avg(duration_ms) mean"
                + " FROM " + T_RUNS + (filtered ? " WHERE job = ?" : "");
        Map<String, Object> m = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) ps.setString(1, job);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long total = rs.getLong("total");
                    long success = rs.getLong("success");
                    m.put("total", total);
                    m.put("success", success);
                    m.put("failed", rs.getLong("failed"));
                    m.put("successRate", total == 0 ? 0.0 : (double) success / total);
                    m.put("p50Ms", total == 0 ? 0.0 : rs.getDouble("p50"));
                    m.put("p95Ms", total == 0 ? 0.0 : rs.getDouble("p95"));
                    m.put("meanMs", total == 0 ? 0.0 : rs.getDouble("mean"));
                }
            }
        } catch (SQLException e) {
            log.warn("job metrics query failed: {}", e.getMessage());
        }
        return m;
    }

    /**
     * The most recent runs (newest first), optionally for one {@code job}; durable across restarts. Columns
     * are aliased to camelCase to match the rest of the JSON API (the frontend consumes these verbatim).
     */
    public synchronized List<Map<String, Object>> recentRuns(int limit, String job) {
        boolean filtered = job != null && !job.isBlank();
        String sql = "SELECT run_id AS \"runId\", job, type, \"trigger\", start_time AS \"startTime\","
                + " end_time AS \"endTime\", status, duration_ms AS \"durationMs\", message"
                + " FROM " + T_RUNS + (filtered ? " WHERE job = ?" : "")
                + " ORDER BY start_time DESC, run_id DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (filtered) ps.setString(i++, job);
            ps.setInt(i, Math.max(1, limit));
            return rows(ps);
        } catch (SQLException e) {
            log.warn("recent job runs query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Failure trend by calendar day (newest first, up to {@code days} distinct days): each entry carries
     * {@code day}, {@code total} and {@code failed}. The day is the {@code start_time} date prefix (the
     * SQL alias is quoted because {@code day} is a DuckDB keyword; the JSON key is the plain {@code day}).
     */
    public synchronized List<Map<String, Object>> failureTrend(int days) {
        String sql = "SELECT substr(start_time,1,10) AS \"day\", count(*) AS total,"
                + " count(*) FILTER (WHERE status<>'SUCCESS') AS failed"
                + " FROM " + T_RUNS + " GROUP BY \"day\" ORDER BY \"day\" DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, days));
            return rows(ps);
        } catch (SQLException e) {
            log.warn("job failure-trend query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Materialise a result set into ordered column→value maps (column labels as returned by the driver). */
    private static List<Map<String, Object>> rows(PreparedStatement ps) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), rs.getObject(c));
                out.add(row);
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing job-run DB: {}", e.getMessage());
        }
    }
}
