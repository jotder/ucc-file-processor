package com.gamma.acquire.connectors;

import com.gamma.acquire.AcquisitionException;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionWorkbench;
import com.gamma.util.JdbcRows;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@link ConnectionWorkbench} for {@code db} connection profiles — probes, explores and samples a JDBC
 * database. Explore walks the live catalog as a {@code schema → table → column} tree (via
 * {@link DatabaseMetaData}); sample runs a bounded {@code SELECT *} against a chosen table. The connection is
 * opened lazily (shared with the export connector through {@link DbConnections}) and held for the workbench's
 * lifetime; {@link #close()} releases it and any SSH tunnel.
 *
 * <p><b>Read-only.</b> The {@code write} probe check is always reported <em>skipped</em> — a workbench must
 * never mutate someone's database to prove write access. Table/schema names chosen for a sample are quoted
 * with the driver's identifier quote so a crafted name cannot break out of the {@code SELECT}.
 */
final class DbConnectionWorkbench implements ConnectionWorkbench {

    private final ConnectionProfile profile;
    private Connection conn;      // opened lazily on first check/explore/sample
    private SshTunnel tunnel;     // non-null only when the profile tunnels through a bastion

    DbConnectionWorkbench(ConnectionProfile profile) {
        this.profile = profile;
    }

    private synchronized Connection conn() throws SQLException {
        if (conn != null && !conn.isClosed()) return conn;
        DbConnections.Handle h = DbConnections.open(profile);
        conn = h.conn();
        tunnel = h.tunnel();
        return conn;
    }

    @Override
    public CheckOutcome check(ProbeCheck check, int sampleLimit) {
        return switch (check) {
            case AUTHENTICATE -> {
                try {
                    String product = conn().getMetaData().getDatabaseProductName();
                    yield CheckOutcome.ok(check, "connected" + (product == null || product.isBlank() ? "" : " to " + product));
                } catch (SQLException e) {
                    yield CheckOutcome.fail(check, "connect failed: " + e.getMessage());
                }
            }
            case READ -> {
                try (ResultSet rs = conn().getMetaData().getSchemas()) {
                    int n = 0;
                    while (rs.next()) n++;
                    yield CheckOutcome.ok(check, n + " schema(s) visible");
                } catch (SQLException e) {
                    yield CheckOutcome.fail(check, "schema catalog unreadable: " + e.getMessage());
                }
            }
            case WRITE -> CheckOutcome.skipped(check, "database workbench is read-only — writes are never probed");
            case LIST -> {
                int cap = Math.max(1, sampleLimit);
                try (ResultSet rs = conn().getMetaData().getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                    int n = 0;
                    while (n < cap && rs.next()) n++;
                    yield CheckOutcome.ok(check, "listed " + n + " table(s)" + (n == cap ? " (capped at " + cap + ")" : ""));
                } catch (SQLException e) {
                    yield CheckOutcome.fail(check, "cannot list tables: " + e.getMessage());
                }
            }
            default -> throw new IllegalStateException("check " + check + " is answered by the prober");
        };
    }

    @Override
    public List<ResourceNode> explore(String path) throws AcquisitionException {
        String p = norm(path);
        String[] parts = p.isEmpty() ? new String[0] : p.split("/");
        try {
            DatabaseMetaData md = conn().getMetaData();
            return switch (parts.length) {
                case 0 -> schemas(md);
                case 1 -> tables(md, parts[0]);
                case 2 -> columns(md, parts[0], parts[1]);
                default -> throw new NoSuchPath("db explore path is '<schema>' or '<schema>/<table>': " + p);
            };
        } catch (SQLException e) {
            throw new AcquisitionException("explore failed for '" + p + "': " + e.getMessage(), e);
        }
    }

    @Override
    public SampleResult sample(String path, int limit) throws AcquisitionException {
        String p = norm(path);
        String[] parts = p.isEmpty() ? new String[0] : p.split("/");
        if (parts.length != 2) throw new NoSuchPath("db sample path is '<schema>/<table>': " + p);
        String schema = parts[0], table = parts[1];
        try {
            Connection c = conn();
            DatabaseMetaData md = c.getMetaData();
            if (!tableExists(md, schema, table))
                throw new NoSuchPath("no such table under the connection: " + schema + "/" + table);
            String fqn = quoteIdent(md, schema) + "." + quoteIdent(md, table);
            int cap = Math.max(1, limit);
            try (Statement st = c.createStatement()) {
                st.setMaxRows(cap + 1);   // vendor-neutral row cap — no LIMIT/TOP dialect guessing
                try (ResultSet rs = st.executeQuery("SELECT * FROM " + fqn)) {
                    List<String> columns = JdbcRows.columnLabels(rs);
                    List<Map<String, Object>> rows = JdbcRows.toMaps(rs);
                    boolean truncated = rows.size() > cap;
                    if (truncated) rows = new ArrayList<>(rows.subList(0, cap));
                    return new SampleResult(schema + "/" + table, columns, rows, truncated, null);
                }
            }
        } catch (SQLException e) {
            throw new AcquisitionException("sample failed for '" + p + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws AcquisitionException {
        SQLException sqlErr = null;
        if (conn != null) {
            try { conn.close(); } catch (SQLException e) { sqlErr = e; }
            conn = null;
        }
        if (tunnel != null) {
            try { tunnel.close(); } catch (IOException ignore) { /* best effort */ }
            tunnel = null;
        }
        if (sqlErr != null) throw new AcquisitionException("closing DB workbench connection failed", sqlErr);
    }

    // ── metadata walkers ────────────────────────────────────────────────────────

    private List<ResourceNode> schemas(DatabaseMetaData md) throws SQLException {
        List<ResourceNode> out = new ArrayList<>();
        try (ResultSet rs = md.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema == null || schema.isBlank()) continue;
                out.add(new ResourceNode(schema, schema, ResourceNode.Kind.SCHEMA, true, null, null, null, null));
            }
        }
        out.sort(Comparator.comparing(ResourceNode::name));
        return out;
    }

    private List<ResourceNode> tables(DatabaseMetaData md, String schema) throws SQLException {
        List<ResourceNode> out = new ArrayList<>();
        try (ResultSet rs = md.getTables(null, schema, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String t = rs.getString("TABLE_NAME");
                if (t == null || t.isBlank()) continue;
                out.add(new ResourceNode(t, schema + "/" + t, ResourceNode.Kind.TABLE, true, null, null, null, null));
            }
        }
        if (out.isEmpty() && !schemaExists(md, schema))
            throw new NoSuchPath("no such schema under the connection: " + schema);
        out.sort(Comparator.comparing(ResourceNode::name));
        return out;
    }

    private List<ResourceNode> columns(DatabaseMetaData md, String schema, String table) throws SQLException {
        List<ResourceNode> out = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
            while (rs.next()) {
                String col = rs.getString("COLUMN_NAME");
                if (col == null || col.isBlank()) continue;
                out.add(new ResourceNode(col, schema + "/" + table + "/" + col,
                        ResourceNode.Kind.COLUMN, false, null, null, null, null));
            }
        }
        if (out.isEmpty()) throw new NoSuchPath("no such table under the connection: " + schema + "/" + table);
        return out;   // driver returns columns in ordinal position — keep that order, don't sort
    }

    private boolean schemaExists(DatabaseMetaData md, String schema) throws SQLException {
        try (ResultSet rs = md.getSchemas()) {
            while (rs.next()) if (schema.equals(rs.getString("TABLE_SCHEM"))) return true;
        }
        return false;
    }

    private boolean tableExists(DatabaseMetaData md, String schema, String table) throws SQLException {
        try (ResultSet rs = md.getTables(null, schema, table, new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) if (table.equals(rs.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    /** Quote an identifier with the driver's quote string (doubling any embedded quote) so a crafted schema/
     *  table name cannot break out of the {@code SELECT}. */
    private static String quoteIdent(DatabaseMetaData md, String ident) throws SQLException {
        String q = md.getIdentifierQuoteString();
        if (q == null || q.equals(" ")) q = "\"";   // JDBC: " " means quoting unsupported → fall back to standard
        return q + ident.replace(q, q + q) + q;
    }

    private static String norm(String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
