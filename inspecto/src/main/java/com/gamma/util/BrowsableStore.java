package com.gamma.util;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A read-only browse seam over a DB-backed operational store's <b>live</b> JDBC connection — the Phase-2
 * hook the raw table browser ({@code DbBrowserRoutes}) uses to page through control-plane tables
 * (design {@code docs/superpower/db-browser-design.md}).
 *
 * <p><b>Why the live connection.</b> Each operational store owns a single-writer-locked DuckDB file; a
 * second connection to it would fail to acquire the lock. So a browse read must run on the store's own
 * {@link #browseConnection()} — and, to never race the store's own writes, inside
 * {@code synchronized (browseMonitor())}. Every {@code Db*Store} guards its data access with
 * {@code synchronized} instance methods (monitor = {@code this}), so the default {@link #browseMonitor()}
 * ({@code this}) reuses that exact monitor.
 *
 * <p>Reads are strictly bounded (server-added {@code LIMIT n+1} truncation probe) and read-only. Ad-hoc
 * SQL passed to {@link #browseQuery} must already have cleared {@code SqlGuard} at the call site. A store
 * whose {@code close()} is not itself synchronised can be torn down mid-read at space shutdown; the
 * resulting {@link SQLException} surfaces to the caller rather than corrupting state (browse is best-effort).
 */
public interface BrowsableStore {

    /** Stable capability id for the browser catalog group, e.g. {@code "objects"}, {@code "jobs"}. */
    String browseId();

    /** Human label for the catalog group, e.g. {@code "Objects"}, {@code "Job Runs"}. */
    String browseLabel();

    /** This store's own table name(s) — its compile-time constants, not JDBC metadata discovery. */
    List<String> browseTables();

    /** The store's live connection. Used only inside the {@code synchronized} blocks of this interface. */
    Connection browseConnection();

    /** The monitor a browse read locks to exclude the store's own writes. Defaults to the store itself. */
    default Object browseMonitor() { return this; }

    /** One page of rows: the {@code truncation} flag is set when more than {@code limit} rows existed. */
    record Column(String name, String type) {}
    record Page(List<Column> columns, List<Map<String, Object>> rows, boolean truncated) {}

    /** {@code "postgres"} or {@code "duckdb"} — for the catalog engine label. Best-effort. */
    default String browseEngine() {
        synchronized (browseMonitor()) {
            return JdbcDrivers.isPostgres(browseConnection()) ? "postgres" : "duckdb";
        }
    }

    /** Paginated {@code SELECT *} over one of this store's own tables (server-built SQL). */
    default Page browseTable(String table, int limit, int offset) throws SQLException {
        if (!browseTables().contains(table))
            throw new IllegalArgumentException("unknown table '" + table + "'");
        return exec("SELECT * FROM " + quoteIdent(table), limit, offset);
    }

    /** Ad-hoc read-only SQL (already {@code SqlGuard}-checked by the caller) over the live connection. */
    default Page browseQuery(String sql, int limit, int offset) throws SQLException {
        return exec(sql, limit, offset);
    }

    /** Wrap the (trusted or guarded) inner SQL with a server-built {@code LIMIT n+1 / OFFSET}, run it under
     *  the store monitor, and materialise typed columns + rows. */
    private Page exec(String innerSql, int limit, int offset) throws SQLException {
        int lim = Math.max(0, limit);
        String wrapped = "SELECT * FROM (" + innerSql + ") AS __q LIMIT " + (lim + 1)
                + " OFFSET " + Math.max(0, offset);
        synchronized (browseMonitor()) {
            try (Statement st = browseConnection().createStatement();
                 ResultSet rs = st.executeQuery(wrapped)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<Column> columns = new ArrayList<>(n);
                for (int c = 1; c <= n; c++) columns.add(new Column(md.getColumnLabel(c), md.getColumnTypeName(c)));
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), wireValue(rs.getObject(c)));
                    rows.add(row);
                }
                boolean truncated = rows.size() > lim;
                if (truncated) rows = rows.subList(0, lim);
                return new Page(columns, rows, truncated);
            }
        }
    }

    /** Coerce {@code java.time} temporals to their ISO string (the control-plane JSON mapper carries no
     *  jsr310 module) — matches {@code QueryExecutor.wireValue}. All other values pass through. */
    private static Object wireValue(Object v) {
        return v instanceof java.time.temporal.Temporal ? v.toString() : v;
    }

    /** Double-quote an identifier for the {@code FROM} clause (table names are validated store constants). */
    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
