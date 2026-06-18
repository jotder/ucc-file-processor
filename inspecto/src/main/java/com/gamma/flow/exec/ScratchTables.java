package com.gamma.flow.exec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Small helpers for seeding sample rows into a throwaway DuckDB table and reading relations back — shared by
 * the dry-run / preview paths ({@link ComponentPreview}, {@link FlowDryRun}). Sample values seed as
 * {@code VARCHAR} columns (the union of the rows' keys), exactly as the preview contract specifies; operator
 * SQL casts as needed, just as in production.
 */
final class ScratchTables {

    private ScratchTables() {}

    /** The ordered union of keys across the sample rows — the seeded table's columns. */
    static List<String> columnsOf(List<Map<String, Object>> rows) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        return new ArrayList<>(cols);
    }

    /** Create {@code table} (all VARCHAR over {@code columns}) and insert the sample rows. */
    static void seed(Connection conn, String table, List<String> columns,
                     List<Map<String, Object>> rows) throws SQLException {
        StringBuilder create = new StringBuilder("CREATE TABLE ").append(q(table)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) create.append(", ");
            create.append(q(columns.get(i))).append(" VARCHAR");
        }
        create.append(")");
        try (Statement st = conn.createStatement()) {
            st.execute(create.toString());
        }
        StringBuilder ins = new StringBuilder("INSERT INTO ").append(q(table)).append(" VALUES (");
        for (int i = 0; i < columns.size(); i++) ins.append(i > 0 ? ",?" : "?");
        ins.append(")");
        try (PreparedStatement ps = conn.prepareStatement(ins.toString())) {
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    Object v = row.get(columns.get(i));
                    ps.setString(i + 1, v == null ? null : v.toString());
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** Row count of {@code table}. */
    static int count(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM " + q(table))) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Read up to {@code cap} rows of {@code table} as ordered column→value maps. */
    static List<Map<String, Object>> readRows(Connection conn, String table, int cap) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + q(table) + " LIMIT " + Math.max(0, cap))) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int c = 1; c <= n; c++) m.put(md.getColumnLabel(c), rs.getObject(c));
                rows.add(m);
            }
        }
        return rows;
    }

    /** Quote a SQL identifier. */
    static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
