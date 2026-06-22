package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
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
 * <b>T21 — durable, queryable projection of the data-plane provenance matrix.</b> Persists the per-(node,
 * relationship) record counts a flow run emits ({@link ProvenanceRow}) into a DuckDB table so a
 * {@code GET /provenance} query (T22) can paint counts onto the {@link com.gamma.flow.FlowGraph} edges of a
 * past run. Mirrors {@link com.gamma.job.DbJobRunStore}: plain JDBC over the bundled DuckDB engine (no new
 * dependency), a single shared {@link Connection} (low-volume, JDBC connections aren't thread-safe), schema
 * created on open.
 *
 * <p>Default-off: activated only when {@code -Dprovenance.backend=duckdb} is set ({@link #close()} owned by the
 * {@link com.gamma.job.JobService} that holds it). When absent, the executor still runs with a {@code NONE}
 * collector and the {@code /provenance} endpoint 404s — nothing about the live path changes.
 */
@PublicApi(since = "4.3.0")
public final class DbProvenanceStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbProvenanceStore.class);
    private static final String T = "inspecto_flow_provenance";

    private final Connection conn;

    /** Wrap an already-open JDBC connection; the schema is created if absent. Takes ownership (closed in {@link #close()}). */
    public DbProvenanceStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /** Open a provenance DB by JDBC URL (DuckDB primary, e.g. {@code jdbc:duckdb:provenance.duckdb}). */
    public static DbProvenanceStore open(String url) throws SQLException {
        return new DbProvenanceStore(JdbcDrivers.connect(url));
    }

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + T + " ("
                    + "flow_id VARCHAR, batch_id VARCHAR, node_id VARCHAR, rel VARCHAR, "
                    + "row_count BIGINT, run_ts VARCHAR)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise provenance DB schema", e);
        }
    }

    /** Append all rows of one flow run. Best-effort: a write failure is logged, never thrown. */
    public synchronized void record(List<ProvenanceRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + T
                + " (flow_id, batch_id, node_id, rel, row_count, run_ts) VALUES (?,?,?,?,?,?)")) {
            for (ProvenanceRow r : rows) {
                ps.setString(1, r.flowId());
                ps.setString(2, r.batchId());
                ps.setString(3, r.nodeId());
                ps.setString(4, r.rel());
                ps.setLong(5, r.rowCount());
                ps.setString(6, r.runTs());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.warn("Could not project provenance for flow {} batch {}: {}",
                    rows.get(0).flowId(), rows.get(0).batchId(), e.getMessage());
        }
    }

    /**
     * The per-(node, relationship) counts of one run, keyed by {@code (flowId, batchId)}. Column labels are
     * camelCase to match the rest of the JSON API (the frontend consumes them verbatim, mapping each
     * {@code (nodeId, rel)} onto its outgoing {@code FlowGraph} edge as the Sankey weight).
     */
    public synchronized List<Map<String, Object>> query(String flowId, String batchId) {
        String sql = "SELECT node_id AS \"nodeId\", rel, row_count AS \"rowCount\""
                + " FROM " + T + " WHERE flow_id = ? AND batch_id = ? ORDER BY node_id, rel";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flowId);
            ps.setString(2, batchId);
            return rows(ps);
        } catch (SQLException e) {
            log.warn("provenance query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** The most recent runs of a flow (distinct {@code batchId}, newest first) — for picking a run to inspect. */
    public synchronized List<Map<String, Object>> batches(String flowId, int limit) {
        String sql = "SELECT batch_id AS \"batchId\", max(run_ts) AS \"runTs\", sum(row_count) AS \"totalRows\""
                + " FROM " + T + " WHERE flow_id = ? GROUP BY batch_id ORDER BY \"runTs\" DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, flowId);
            ps.setInt(2, Math.max(1, limit));
            return rows(ps);
        } catch (SQLException e) {
            log.warn("provenance batches query failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

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
            log.warn("Error closing provenance DB: {}", e.getMessage());
        }
    }
}
