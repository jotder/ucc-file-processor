package com.gamma.ops.link;

import com.gamma.ops.ObjectType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Database-backed {@link LinkStore} — the durable correlation graph. The twin of
 * {@link com.gamma.ops.DbObjectStore}: plain JDBC over portable SQL, primary engine the already-bundled
 * DuckDB (so it adds <em>no</em> new dependency), and a {@code jdbc:postgresql://…} URL serves a
 * distributed deployment. Pick the engine by JDBC URL via {@link #open(String, String, String)}.
 *
 * <p>Links are append-only facts, so — unlike the object store — there is no {@code UPDATE}; the table
 * just grows and ages out with its objects. All access is serialised on a single shared
 * {@link Connection} (low-volume; a JDBC connection is not thread-safe); {@link #close()} closes it.
 *
 * @since 4.5.0
 */
@com.gamma.api.PublicApi(since = "4.5.0")
public final class DbLinkStore implements LinkStore {

    private static final Logger log = LoggerFactory.getLogger(DbLinkStore.class);

    private static final String TABLE = "inspecto_ops_links";
    private static final String COLS = "from_id, from_type, to_id, to_type, relationship, created_at";

    private final Connection conn;

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbLinkStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /**
     * Open a link DB by JDBC URL, registering the matching driver — {@code jdbc:duckdb:} (bundled
     * primary) and {@code jdbc:postgresql:} explicitly, any other URL via {@link DriverManager}.
     */
    public static DbLinkStore open(String url, String user, String pass) throws SQLException {
        try {
            if (url.startsWith("jdbc:duckdb:")) Class.forName("org.duckdb.DuckDBDriver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No JDBC driver on the classpath for " + url, e);
        }
        Connection c = (user == null && pass == null)
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, pass);
        return new DbLinkStore(c);
    }

    @Override
    public synchronized ObjectLink add(ObjectLink link) {
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, link.fromId());
            ps.setString(2, link.fromType().name());
            ps.setString(3, link.toId());
            ps.setString(4, link.toType().name());
            ps.setString(5, link.relationship());
            ps.setLong(6, link.createdAt());
            ps.executeUpdate();
            return link;
        } catch (SQLException e) {
            throw new IllegalStateException("could not insert link " + link.fromId() + "->" + link.toId()
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<ObjectLink> incident(String objectId) {
        String sql = "SELECT " + COLS + " FROM " + TABLE
                + " WHERE from_id = ? OR to_id = ? ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, objectId);
            ps.setString(2, objectId);
            return readAll(ps);
        } catch (SQLException e) {
            log.warn("link incident query failed for {}: {}", objectId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public synchronized List<ObjectLink> all(int limit) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " ORDER BY created_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, limit));
            return readAll(ps);
        } catch (SQLException e) {
            log.warn("link query failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing link DB connection: {}", e.getMessage());
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "from_id VARCHAR, from_type VARCHAR, to_id VARCHAR, to_type VARCHAR, "
                    + "relationship VARCHAR, created_at BIGINT)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise link DB schema", e);
        }
    }

    private static List<ObjectLink> readAll(PreparedStatement ps) throws SQLException {
        List<ObjectLink> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ObjectLink(
                        rs.getString("from_id"),
                        ObjectType.valueOf(rs.getString("from_type")),
                        rs.getString("to_id"),
                        ObjectType.valueOf(rs.getString("to_type")),
                        rs.getString("relationship"),
                        rs.getLong("created_at")));
            }
        }
        return out;
    }
}
