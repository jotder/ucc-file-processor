package com.gamma.ops.note;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.util.JdbcDrivers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database-backed {@link NoteStore} — the durable evidence/notes store. The twin of
 * {@link com.gamma.ops.DbObjectStore} (it reuses the same attribute-JSON idiom) but append-only like
 * {@link com.gamma.ops.link.DbLinkStore}: plain JDBC over the already-bundled DuckDB (no new
 * dependency), or a {@code jdbc:postgresql://…} URL for a distributed deployment. All access is
 * serialised on a single shared {@link Connection}; {@link #close()} closes it.
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.6.0")
public final class DbNoteStore implements NoteStore {

    private static final Logger log = LoggerFactory.getLogger(DbNoteStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> ATTRS = new TypeReference<>() {};

    private static final String TABLE = "inspecto_ops_notes";
    private static final String COLS = "id, object_id, kind, author, body, attributes, created_at";

    private final Connection conn;

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbNoteStore(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /**
     * Open a note DB by JDBC URL via {@link JdbcDrivers#connect(String, String, String)}, which
     * registers the bundled driver matching the scheme ({@code jdbc:duckdb:} primary, {@code jdbc:postgresql:}).
     */
    public static DbNoteStore open(String url, String user, String pass) throws SQLException {
        return new DbNoteStore(JdbcDrivers.connect(url, user, pass));
    }

    @Override
    public synchronized ObjectNote add(ObjectNote note) {
        String sql = "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, note.id());
            ps.setString(2, note.objectId());
            ps.setString(3, note.kind().name());
            ps.setString(4, note.author());
            ps.setString(5, note.body());
            ps.setString(6, render(note.attributes()));
            ps.setLong(7, note.createdAt());
            ps.executeUpdate();
            return note;
        } catch (SQLException e) {
            throw new IllegalStateException("could not insert note " + note.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<ObjectNote> forObject(String objectId, NoteKind kind) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE object_id = ?"
                + (kind == null ? "" : " AND kind = ?") + " ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, objectId);
            if (kind != null) ps.setString(2, kind.name());
            List<ObjectNote> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
            return out;
        } catch (SQLException e) {
            log.warn("note query failed for {}: {}", objectId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing note DB connection: {}", e.getMessage());
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id VARCHAR PRIMARY KEY, object_id VARCHAR, kind VARCHAR, author VARCHAR, "
                    + "body VARCHAR, attributes VARCHAR, created_at BIGINT)");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise note DB schema", e);
        }
    }

    private static ObjectNote mapRow(ResultSet rs) throws SQLException {
        return new ObjectNote(
                rs.getString("id"),
                rs.getString("object_id"),
                NoteKind.valueOf(rs.getString("kind")),
                rs.getString("author"),
                rs.getString("body"),
                parse(rs.getString("attributes")),
                rs.getLong("created_at"));
    }

    private static String render(Map<String, String> attrs) {
        try {
            return JSON.writeValueAsString(attrs == null ? Map.of() : attrs);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, ATTRS);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
