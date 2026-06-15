package com.gamma.acquire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Database-backed {@link AcquisitionLedger} — durable fingerprint repository over the already-bundled DuckDB
 * (no new dependency), or a {@code jdbc:postgresql://…} URL for a distributed deployment. Mirrors the OI store
 * pattern ({@link com.gamma.ops.note.DbNoteStore}): plain JDBC, all access serialised on a single shared
 * {@link Connection}, schema created on open. Lives in its <b>own</b> DB file (default
 * {@code inspecto-acquisition.db}) because a file DuckDB holds a single-writer lock and the status/object/link/
 * note stores already own theirs.
 *
 * <p><b>Upsert semantics:</b> the {@code (source_id, relative_path)} primary key holds one row per file; a new
 * fingerprint replaces the prior one (DELETE-then-INSERT under the synchronized lock) so a re-uploaded/changed
 * file's latest state is what later cycles compare against.
 */
public final class DbAcquisitionLedger implements AcquisitionLedger {

    private static final Logger log = LoggerFactory.getLogger(DbAcquisitionLedger.class);

    private static final String TABLE = "inspecto_acquisition_ledger";
    private static final String COLS = "source_id, relative_path, name, size, checksum, last_modified, processed_at, status";
    private static final String WM_TABLE = "inspecto_acquisition_db_watermark";

    private final Connection conn;

    /** Wrap an already-open JDBC connection (any engine); the schema is created if absent. */
    public DbAcquisitionLedger(Connection conn) {
        this.conn = conn;
        initSchema();
    }

    /** Open a ledger DB by JDBC URL, registering the matching driver (bundled DuckDB, or Postgres). */
    public static DbAcquisitionLedger open(String url, String user, String pass) throws SQLException {
        try {
            if (url.startsWith("jdbc:duckdb:")) Class.forName("org.duckdb.DuckDBDriver");
            else if (url.startsWith("jdbc:postgresql:")) Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("No JDBC driver on the classpath for " + url, e);
        }
        Connection c = (user == null && pass == null)
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, pass);
        return new DbAcquisitionLedger(c);
    }

    @Override
    public synchronized Optional<LedgerEntry> find(String sourceId, String relativePath) {
        String sql = "SELECT " + COLS + " FROM " + TABLE + " WHERE source_id = ? AND relative_path = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceId);
            ps.setString(2, relativePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("ledger lookup failed for {}/{}: {}", sourceId, relativePath, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public synchronized void record(LedgerEntry e) {
        try {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE source_id = ? AND relative_path = ?")) {
                del.setString(1, e.sourceId());
                del.setString(2, e.relativePath());
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO " + TABLE + " (" + COLS + ") VALUES (?,?,?,?,?,?,?,?)")) {
                ins.setString(1, e.sourceId());
                ins.setString(2, e.relativePath());
                ins.setString(3, e.name());
                ins.setLong(4, e.size());
                ins.setString(5, e.checksum());
                ins.setLong(6, e.lastModified());
                ins.setLong(7, e.processedAt());
                ins.setString(8, e.status());
                ins.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "could not record ledger entry " + e.sourceId() + "/" + e.relativePath() + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public synchronized OptionalLong highWatermark(String sourceId) {
        String sql = "SELECT MAX(last_modified) FROM " + TABLE + " WHERE source_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    if (!rs.wasNull()) return OptionalLong.of(v);   // MAX over an empty set is SQL NULL
                }
                return OptionalLong.empty();
            }
        } catch (SQLException e) {
            log.warn("watermark lookup failed for {}: {}", sourceId, e.getMessage());
            return OptionalLong.empty();   // degrade safely: an unavailable watermark just means no skipping
        }
    }

    @Override
    public synchronized Optional<String> dbWatermark(String sourceKey) {
        String sql = "SELECT watermark_value FROM " + WM_TABLE + " WHERE source_key = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sourceKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.warn("db-watermark lookup failed for {}: {}", sourceKey, e.getMessage());
            return Optional.empty();   // degrade safely: an unavailable watermark just re-exports from the floor
        }
    }

    @Override
    public synchronized void recordDbWatermark(String sourceKey, String value) {
        if (value == null) return;
        try {
            try (PreparedStatement del = conn.prepareStatement(
                    "DELETE FROM " + WM_TABLE + " WHERE source_key = ?")) {
                del.setString(1, sourceKey);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO " + WM_TABLE + " (source_key, watermark_value, advanced_at) VALUES (?,?,?)")) {
                ins.setString(1, sourceKey);
                ins.setString(2, value);
                ins.setLong(3, System.currentTimeMillis());
                ins.executeUpdate();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "could not record db watermark for " + sourceKey + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warn("Error closing acquisition-ledger DB connection: {}", e.getMessage());
        }
    }

    // ── schema + helpers ─────────────────────────────────────────────────────────

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "source_id VARCHAR, relative_path VARCHAR, name VARCHAR, size BIGINT, "
                    + "checksum VARCHAR, last_modified BIGINT, processed_at BIGINT, status VARCHAR, "
                    + "PRIMARY KEY (source_id, relative_path))");
            // Row-level DB-export watermark (resumable incremental export): one opaque value per source key,
            // advanced only after a batch commits. Its own table, not a fake row in the fingerprint table.
            st.execute("CREATE TABLE IF NOT EXISTS " + WM_TABLE + " ("
                    + "source_key VARCHAR, watermark_value VARCHAR, advanced_at BIGINT, "
                    + "PRIMARY KEY (source_key))");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialise acquisition-ledger DB schema", e);
        }
    }

    private static LedgerEntry mapRow(ResultSet rs) throws SQLException {
        return new LedgerEntry(
                rs.getString("source_id"),
                rs.getString("relative_path"),
                rs.getString("name"),
                rs.getLong("size"),
                rs.getString("checksum"),
                rs.getLong("last_modified"),
                rs.getLong("processed_at"),
                rs.getString("status"));
    }
}
