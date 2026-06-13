package com.gamma.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Database-backed {@link StatusStore} (M5) — the durable, queryable alternative to
 * {@link FileStatusStore}, sitting behind the exact same seam so the Control API and
 * observability layers built on it don't change.
 *
 * <h3>Engine portability</h3>
 * The implementation is plain JDBC over portable ANSI SQL (no UPSERT, no engine-specific
 * types), so a single code path runs on whatever JDBC engine the URL names. The <b>primary,
 * default engine is DuckDB</b> — already bundled for ingest/enrichment, so the DB backend
 * adds <em>no</em> new dependency and the same engine serves tests and production (a local
 * file, embedded, single-process). <b>PostgreSQL</b> remains supported by the same code for a
 * future distributed / multi-writer deployment: pass a {@code jdbc:postgresql://…} URL and put
 * the PG JDBC driver on the classpath (bring-your-own — it is not bundled). Pick the engine by
 * JDBC URL via {@link #open(String, String, String)}.
 *
 * <h3>Model — a projection of the on-disk audit</h3>
 * Stage-1 ingest still writes its file audit artifacts unchanged (they remain the
 * write-time source of truth and survive a DB outage). This store is a <em>projection</em>
 * of those artifacts: {@link #sync(StatusStore, Collection)} reads them through a source
 * {@link StatusStore} and rewrites each pipeline's rows transactionally
 * (DELETE-then-INSERT — idempotent, so a re-sync is a refresh). {@code SourceService}
 * calls it at startup and after each poll cycle, so the DB always reflects the latest
 * committed state by the time anything queries it.
 *
 * <h3>Schema</h3>
 * The audit rows are dynamic {@code header → value} maps (columns vary by artifact and
 * carry list-valued fields), so each row's map is stored verbatim as a JSON {@code payload}
 * plus the few columns we actually index on ({@code pipeline}, {@code batch_id},
 * {@code seq} for stable ordering). This round-trips the maps faithfully and serialises
 * straight back to JSON for the API.
 *
 * <p>All access is serialised on a single shared {@link Connection}: status traffic is
 * low-volume, an in-memory DuckDB database only exists for the life of one connection,
 * and a JDBC {@code Connection} is not thread-safe. {@link #close()} closes it.
 */
@PublicApi(since = "2.6.0")
public final class DbStatusStore implements StatusStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbStatusStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> ROW =
            new TypeReference<>() {};

    private static final String T_COMMITS    = "inspecto_status_commits";
    private static final String T_BATCHES    = "inspecto_status_batches";
    private static final String T_FILES      = "inspecto_status_files";
    private static final String T_LINEAGE    = "inspecto_status_lineage";
    private static final String T_QUARANTINE = "inspecto_status_quarantine";

    private final Connection conn;

    /**
     * Wrap an already-open JDBC connection (any engine). The schema is created if absent.
     * The store takes ownership of the connection and closes it in {@link #close()}.
     */
    public DbStatusStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /**
     * Open a status DB by JDBC URL, registering the matching driver. {@code jdbc:duckdb:}
     * (the bundled primary engine) and {@code jdbc:postgresql:} (driver supplied by the
     * deployment) are registered explicitly; any other URL is passed through to
     * {@link DriverManager} as-is (assumes the driver self-registers). A Postgres URL with
     * no PG driver on the classpath fails with a clear message — it is not bundled.
     *
     * @param url  JDBC URL (e.g. {@code jdbc:duckdb:inspecto-status.db})
     * @param user username, or {@code null} for URL-embedded / no credentials
     * @param pass password, or {@code null}
     */
    public static DbStatusStore open(String url, String user, String pass) throws SQLException {
        try {
            if (url.startsWith("jdbc:duckdb:")) Class.forName("org.duckdb.DuckDBDriver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No JDBC driver on the classpath for " + url, e);
        }
        Connection c = (user == null && pass == null)
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, pass);
        return new DbStatusStore(c);
    }

    // ── reads (StatusStore) ──────────────────────────────────────────────────────

    @Override
    public synchronized Set<String> committedBatches(PipelineConfig cfg) {
        Set<String> ids = new LinkedHashSet<>();
        String p = name(cfg);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT batch_id FROM " + T_COMMITS + " WHERE pipeline = ?")) {
            ps.setString(1, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("committedBatches query failed for {}: {}", p, e.getMessage());
        }
        return ids;
    }

    @Override
    public List<Map<String, String>> batches(PipelineConfig cfg) {
        return readRows(T_BATCHES, name(cfg), null);
    }

    @Override
    public List<Map<String, String>> files(PipelineConfig cfg) {
        return readRows(T_FILES, name(cfg), null);
    }

    @Override
    public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) {
        String b = (batchId == null || batchId.isBlank()) ? null : batchId;
        return readRows(T_LINEAGE, name(cfg), b);
    }

    @Override
    public List<Map<String, String>> quarantine(PipelineConfig cfg) {
        return readRows(T_QUARANTINE, name(cfg), null);
    }

    /** Read a table's payload rows for one pipeline (ordered by seq), optionally filtered by batch_id. */
    private synchronized List<Map<String, String>> readRows(String table, String pipeline, String batchId) {
        List<Map<String, String>> out = new ArrayList<>();
        String sql = "SELECT payload FROM " + table + " WHERE pipeline = ?"
                + (batchId != null ? " AND batch_id = ?" : "") + " ORDER BY seq";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pipeline);
            if (batchId != null) ps.setString(2, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(parse(rs.getString(1)));
            }
        } catch (SQLException e) {
            log.warn("{} query failed for {}: {}", table, pipeline, e.getMessage());
        }
        return out;
    }

    // ── sync (file → DB projection) ──────────────────────────────────────────────

    /**
     * Refresh this DB from a {@code source} status store (typically a {@link FileStatusStore})
     * for the given pipelines. Each pipeline's rows are rewritten in a single transaction
     * — DELETE the pipeline's existing rows, then INSERT the source's current rows — so the
     * operation is idempotent and a partially-applied sync never leaves a torn pipeline.
     */
    public synchronized void sync(StatusStore source, Collection<PipelineConfig> cfgs) {
        boolean autoCommit = true;
        try {
            autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            for (PipelineConfig cfg : cfgs) {
                String p = name(cfg);
                deletePipeline(p);
                insertCommits(p, source.committedBatches(cfg));
                insertRows(T_BATCHES, p, source.batches(cfg), null);
                insertRows(T_LINEAGE, p, source.lineage(cfg, null), "batch_id");
                insertRows(T_FILES, p, source.files(cfg), null);
                insertRows(T_QUARANTINE, p, source.quarantine(cfg), null);
            }
            conn.commit();
        } catch (SQLException e) {
            log.warn("Status DB sync failed, rolling back: {}", e.getMessage());
            try { conn.rollback(); } catch (SQLException ignore) { /* best effort */ }
        } finally {
            try { conn.setAutoCommit(autoCommit); } catch (SQLException ignore) { /* best effort */ }
        }
    }

    private void deletePipeline(String pipeline) throws SQLException {
        for (String t : List.of(T_COMMITS, T_BATCHES, T_FILES, T_LINEAGE, T_QUARANTINE)) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + t + " WHERE pipeline = ?")) {
                ps.setString(1, pipeline);
                ps.executeUpdate();
            }
        }
    }

    private void insertCommits(String pipeline, Set<String> ids) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + T_COMMITS + " (pipeline, batch_id) VALUES (?, ?)")) {
            for (String id : ids) {
                ps.setString(1, pipeline);
                ps.setString(2, id);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Insert payload rows for one pipeline. When {@code batchIdKey} is non-null the
     * row's value for that key is also stored in the indexed {@code batch_id} column
     * (lineage), so {@link #lineage} can filter without parsing every payload.
     */
    private void insertRows(String table, String pipeline,
                            List<Map<String, String>> rows, String batchIdKey) throws SQLException {
        boolean withBatchId = batchIdKey != null;
        String sql = withBatchId
                ? "INSERT INTO " + table + " (pipeline, batch_id, seq, payload) VALUES (?, ?, ?, ?)"
                : "INSERT INTO " + table + " (pipeline, seq, payload) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long seq = 0;
            for (Map<String, String> row : rows) {
                int i = 1;
                ps.setString(i++, pipeline);
                if (withBatchId) ps.setString(i++, row.get(batchIdKey));
                ps.setLong(i++, seq++);
                ps.setString(i, render(row));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        migrateLegacyTables();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + T_COMMITS
                    + " (pipeline VARCHAR, batch_id VARCHAR)");
            st.execute("CREATE TABLE IF NOT EXISTS " + T_BATCHES
                    + " (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE IF NOT EXISTS " + T_FILES
                    + " (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE IF NOT EXISTS " + T_LINEAGE
                    + " (pipeline VARCHAR, batch_id VARCHAR, seq BIGINT, payload VARCHAR)");
            st.execute("CREATE TABLE IF NOT EXISTS " + T_QUARANTINE
                    + " (pipeline VARCHAR, seq BIGINT, payload VARCHAR)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise status DB schema", e);
        }
    }

    /**
     * One-time rename of pre-rebrand {@code ucc_status_*} tables to {@code inspecto_status_*}
     * so existing status databases keep their history. No-op on fresh databases.
     */
    private void migrateLegacyTables() {
        String[] suffixes = {"commits", "batches", "files", "lineage", "quarantine"};
        try (Statement st = conn.createStatement()) {
            for (String s : suffixes) {
                String legacy = "ucc_status_" + s;
                String current = "inspecto_status_" + s;
                if (tableExists(legacy) && !tableExists(current)) {
                    st.execute("ALTER TABLE " + legacy + " RENAME TO " + current);
                    log.info("Status DB: renamed legacy table {} -> {}", legacy, current);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not migrate legacy status tables", e);
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String name(PipelineConfig cfg) {
        return cfg.identity().pipelineName();
    }

    private static String render(Map<String, String> row) {
        try {
            return JSON.writeValueAsString(row);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise audit row", e);
        }
    }

    private static Map<String, String> parse(String payload) {
        try {
            return JSON.readValue(payload, ROW);   // LinkedHashMap → preserves column order
        } catch (Exception e) {
            log.warn("Skipping unparseable status payload: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing status DB connection: {}", e.getMessage());
        }
    }
}
