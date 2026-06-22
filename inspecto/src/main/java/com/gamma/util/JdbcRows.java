package com.gamma.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materialises a JDBC {@link ResultSet} into plain, ordered column→value structures — the generic
 * row-mapping idiom shared by the DuckDB-backed stores and the SQL preview/oracle paths. Column keys
 * are the driver-reported labels (so a {@code SELECT col AS "camelCase"} alias surfaces verbatim in
 * the JSON API), and insertion order is preserved via {@link LinkedHashMap}.
 */
public final class JdbcRows {

    private JdbcRows() {}

    /** Map every remaining row of {@code rs} to an ordered {@code columnLabel → value} map. Does not close {@code rs}. */
    public static List<Map<String, Object>> toMaps(ResultSet rs) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), rs.getObject(c));
            out.add(row);
        }
        return out;
    }

    /** Execute {@code ps} and map its result set to ordered {@code columnLabel → value} maps. */
    public static List<Map<String, Object>> query(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            return toMaps(rs);
        }
    }

    /** The column labels of {@code rs}, in result order (driver-reported labels). */
    public static List<String> columnLabels(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> cols = new ArrayList<>(n);
        for (int c = 1; c <= n; c++) cols.add(md.getColumnLabel(c));
        return cols;
    }
}
