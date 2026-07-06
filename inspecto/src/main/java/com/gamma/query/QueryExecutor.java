package com.gamma.query;

import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs a resolved, {@code SqlGuard}-checked query against a space's data in an ephemeral DuckDB
 * sandbox and returns a typed {@link Result} (W4; design §6.2). Mirrors {@code ViewQuery}: the sandbox
 * is opened <b>unsealed</b> because the dataset relation legitimately reads Parquet by absolute path;
 * the safety boundary is that the caller-authored query {@code sql} has already passed {@code SqlGuard}
 * (single read-only SELECT, no file/extension functions) and only the trusted, server-built
 * {@code relationSql} is registered as the dataset view.
 */
public final class QueryExecutor {

    private QueryExecutor() {}

    private static final String DEFAULT_ALIAS = "__q";
    private static final Pattern SAFE_COL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** One ORDER BY term. */
    public record Sort(String field, boolean descending) {}

    /**
     * @param datasetName the logical name the {@code sql} references (registered as a view); {@code null} if none
     * @param relationSql trusted relation SQL for the dataset ({@code CREATE VIEW datasetName AS relationSql}); {@code null} if none
     * @param sql         the resolved, SqlGuard-checked query text
     * @param limit       max rows to return (a further row is read to detect truncation)
     * @param offset      rows to skip
     * @param projection  output columns, or empty for all
     * @param sort        ORDER BY terms, or empty
     */
    public record Request(String datasetName, String relationSql, String sql,
                          int limit, int offset, List<String> projection, List<Sort> sort) {}

    /** The typed, bounded result. */
    public record Result(List<ResultSetDescriptor.Column> columns, List<Map<String, Object>> rows,
                         int rowCount, boolean truncated, long elapsedMs) {}

    public static Result run(Request req) throws SQLException, IOException {
        long t0 = System.nanoTime();
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            Connection conn = sandbox.connection();
            // Trusted registration: the ONLY place file-reading SQL runs (unsealed). The user query below
            // was SqlGuard-checked upstream, so it cannot itself read files.
            if (req.datasetName() != null && req.relationSql() != null) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE VIEW " + q(req.datasetName()) + " AS " + req.relationSql());
                }
            }
            String wrapped = wrap(req);
            try (Statement st = sandbox.statement();
                 ResultSet rs = st.executeQuery(wrapped)) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                List<String> names = new ArrayList<>(n);
                List<Integer> types = new ArrayList<>(n);
                for (int c = 1; c <= n; c++) {
                    names.add(md.getColumnLabel(c));
                    types.add(md.getColumnType(c));
                }
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= n; c++) row.put(md.getColumnLabel(c), rs.getObject(c));
                    rows.add(row);
                }
                boolean truncated = rows.size() > req.limit();
                if (truncated) rows = rows.subList(0, req.limit());
                List<ResultSetDescriptor.Column> columns = ResultSetDescriptor.describe(names, types, rows);
                return new Result(columns, rows, rows.size(), truncated, (System.nanoTime() - t0) / 1_000_000);
            }
        }
    }

    /** Wrap the user query with server-built projection / ORDER BY / LIMIT+1 / OFFSET (all identifier-safe). */
    private static String wrap(Request req) {
        String projection = (req.projection() == null || req.projection().isEmpty())
                ? "*"
                : req.projection().stream().map(QueryExecutor::safeCol).map(QueryExecutor::q)
                    .reduce((a, b) -> a + ", " + b).orElse("*");
        StringBuilder sb = new StringBuilder("SELECT ").append(projection)
                .append(" FROM (").append(req.sql()).append(") AS ").append(q(DEFAULT_ALIAS));
        if (req.sort() != null && !req.sort().isEmpty()) {
            sb.append(" ORDER BY ");
            for (int i = 0; i < req.sort().size(); i++) {
                Sort s = req.sort().get(i);
                if (i > 0) sb.append(", ");
                sb.append(q(safeCol(s.field()))).append(s.descending() ? " DESC" : " ASC");
            }
        }
        sb.append(" LIMIT ").append(Math.max(0, req.limit()) + 1)
          .append(" OFFSET ").append(Math.max(0, req.offset()));
        return sb.toString();
    }

    /** Validate a caller-supplied column identifier (projection/sort) — rejects anything but a plain identifier. */
    private static String safeCol(String col) {
        if (col == null || !SAFE_COL.matcher(col).matches())
            throw new IllegalArgumentException("unsafe column identifier '" + col + "'");
        return col;
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
