package com.gamma.ops;

import com.gamma.util.JdbcDrivers;
import com.gamma.util.JsonAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Database-backed {@link ObjectStore} — the durable Layer-2 store for mutable operational objects.
 * Deliberately the twin of {@link com.gamma.service.DbStatusStore}: plain JDBC over portable SQL, the
 * <b>primary engine is the already-bundled DuckDB</b> (so the object store adds <em>no</em> new
 * dependency), and a {@code jdbc:postgresql://…} URL with the PG driver on the classpath serves a
 * future distributed deployment. Pick the engine by JDBC URL via {@link #open(String, String, String)}.
 *
 * <p>Unlike the status store (a DELETE-then-INSERT projection of immutable audit), objects are the
 * source of truth and they <b>mutate</b>, so this store does a real {@code UPDATE} on a status change.
 * All access is serialised on a single shared {@link Connection} (low-volume traffic; a JDBC
 * connection is not thread-safe); {@link #close()} closes it.
 *
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.3.0")
public final class DbObjectStore implements ObjectStore, com.gamma.util.BrowsableStore {

    private static final Logger log = LoggerFactory.getLogger(DbObjectStore.class);

    private static final String TABLE = "inspecto_ops_objects";
    /** {@code owner} is quoted — it is a reserved word in some SQL dialects. */
    private static final String COLS = "id, object_type, title, description, status, severity, priority, "
            + "\"owner\", assignee, correlation_id, attributes, created_at, updated_at, closed_at";

    private final Connection conn;

    // ── raw table browser seam (BrowsableStore) — read-only, synchronized(this) ──
    @Override public String browseId() { return "objects"; }
    @Override public String browseLabel() { return "Objects"; }
    @Override public java.util.List<String> browseTables() { return java.util.List.of(TABLE); }
    @Override public Connection browseConnection() { return conn; }

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbObjectStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /**
     * Open an object DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:} primary,
     * {@code jdbc:postgresql:} deployment-supplied).
     *
     * @param url  JDBC URL (e.g. {@code jdbc:duckdb:inspecto-ops.db})
     * @param user username, or {@code null}
     * @param pass password, or {@code null}
     */
    public static DbObjectStore open(String url, String user, String pass) throws SQLException {
        return new DbObjectStore(JdbcDrivers.connect(url, user, pass));
    }

    @Override
    public synchronized OperationalObject create(OperationalObject obj) {
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bindAll(ps, obj);
            ps.executeUpdate();
            return obj;
        } catch (SQLException e) {
            // A duplicate id trips the primary-key constraint — surface it as the SPI's contract type.
            if (get(obj.id()).isPresent())
                throw new IllegalStateException("object already exists: " + obj.id());
            throw new IllegalStateException("could not insert object " + obj.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Optional<OperationalObject> get(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + COLS + " FROM " + TABLE + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("object get failed for {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized OperationalObject update(OperationalObject obj) {
        String sql = "UPDATE " + TABLE + " SET object_type=?, title=?, description=?, status=?, "
                + "severity=?, priority=?, \"owner\"=?, assignee=?, correlation_id=?, attributes=?, "
                + "created_at=?, updated_at=?, closed_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // Same column order as bindAll minus the leading id, then id as the WHERE param last.
            ps.setString(1, obj.objectType().name());
            ps.setString(2, obj.title());
            ps.setString(3, obj.description());
            ps.setString(4, obj.status());
            ps.setString(5, obj.severity());
            ps.setString(6, obj.priority());
            ps.setString(7, obj.owner());
            ps.setString(8, obj.assignee());
            ps.setString(9, obj.correlationId());
            ps.setString(10, JsonAttributes.toJson(obj.attributes()));
            ps.setLong(11, obj.createdAt());
            ps.setLong(12, obj.updatedAt());
            ps.setLong(13, obj.closedAt());
            ps.setString(14, obj.id());
            if (ps.executeUpdate() == 0)
                throw new NoSuchElementException("no object with id '" + obj.id() + "'");
            return obj;
        } catch (SQLException e) {
            throw new IllegalStateException("could not update object " + obj.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<OperationalObject> query(ObjectQuery q) {
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.objectType() != null) { where.add("object_type = ?"); params.add(q.objectType().name()); }
        ciEquals(where, params, "status", q.status());
        ciEquals(where, params, "severity", q.severity());
        ciEquals(where, params, "assignee", q.assignee());
        ciEquals(where, params, "\"owner\"", q.owner());
        if (q.correlationId() != null) { where.add("correlation_id = ?"); params.add(q.correlationId()); }
        if (q.textContains() != null) {
            where.add("(LOWER(title) LIKE ? OR LOWER(description) LIKE ?)");
            String like = "%" + q.textContains().toLowerCase(Locale.ROOT) + "%";
            params.add(like);
            params.add(like);
        }
        String sql = "SELECT " + COLS + " FROM " + TABLE
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<OperationalObject> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (Object p : params) ps.setObject(i++, p);
            ps.setInt(i++, q.limit());
            ps.setInt(i, q.offset());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.warn("object query failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing object DB connection: {}", e.getMessage());
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id VARCHAR PRIMARY KEY, object_type VARCHAR, title VARCHAR, description VARCHAR, "
                    + "status VARCHAR, severity VARCHAR, priority VARCHAR, \"owner\" VARCHAR, "
                    + "assignee VARCHAR, correlation_id VARCHAR, attributes VARCHAR, "
                    + "created_at BIGINT, updated_at BIGINT, closed_at BIGINT)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise object DB schema", e);
        }
    }

    private static void bindAll(PreparedStatement ps, OperationalObject o) throws SQLException {
        ps.setString(1, o.id());
        ps.setString(2, o.objectType().name());
        ps.setString(3, o.title());
        ps.setString(4, o.description());
        ps.setString(5, o.status());
        ps.setString(6, o.severity());
        ps.setString(7, o.priority());
        ps.setString(8, o.owner());
        ps.setString(9, o.assignee());
        ps.setString(10, o.correlationId());
        ps.setString(11, JsonAttributes.toJson(o.attributes()));
        ps.setLong(12, o.createdAt());
        ps.setLong(13, o.updatedAt());
        ps.setLong(14, o.closedAt());
    }

    private static OperationalObject mapRow(ResultSet rs) throws SQLException {
        return new OperationalObject(
                rs.getString("id"),
                ObjectType.valueOf(rs.getString("object_type")),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getString("severity"),
                rs.getString("priority"),
                rs.getString("owner"),
                rs.getString("assignee"),
                rs.getString("correlation_id"),
                JsonAttributes.fromJson(rs.getString("attributes")),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("closed_at"));
    }

    private static void ciEquals(List<String> where, List<Object> params, String col, String val) {
        if (val != null) {
            where.add("LOWER(" + col + ") = ?");
            params.add(val.toLowerCase(Locale.ROOT));
        }
    }
}
