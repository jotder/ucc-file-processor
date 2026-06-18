package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.flow.FlowNode;
import com.gamma.util.DuckDbUtil;

import java.io.File;
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
 * <b>T18 — per-component dry-run / preview (validate + bounded sample, scratch-only).</b> Runs a
 * {@code transform.*} node over a handful of sample rows and returns the produced named relations, so the
 * UI can "test a component in isolation" (doc §7.2). It reuses the <em>production</em> row-shaping logic
 * ({@link RowShaper}) — no divergent test path — executed against a throwaway embedded DuckDB seeded from
 * the sample; the scratch database is deleted afterwards, so it never touches any real output.
 *
 * <p>Single-input transforms only ({@code filter}/{@code validate}/{@code route}/{@code dedup}/{@code split}/
 * {@code map}/{@code select}/{@code derive}); {@code transform.merge} is multi-input and not previewable here.
 * Sample values are seeded as {@code VARCHAR} columns (the union of the rows' keys); operator predicates cast
 * as needed, exactly as in production.
 */
@PublicApi(since = "4.3.0")
public final class ComponentPreview {

    private ComponentPreview() {}

    /** The maximum rows returned per produced relation (a preview is bounded). */
    public static final int MAX_ROWS = 1000;

    private static final String INPUT = "preview_input";

    /** One produced relation in a preview: the {@link com.gamma.flow.FlowRel} and the sampled output rows. */
    public record RelationPreview(String rel, int rowCount, List<Map<String, Object>> rows) {}

    /** The preview outcome: the input column set + every relation the node produced over the sample. */
    public record Result(List<String> inputColumns, List<RelationPreview> relations) {}

    /**
     * Preview {@code node} (a {@code transform.*} node) over {@code sampleRows}. Throws
     * {@link IllegalArgumentException} for an empty sample or a non-previewable node type.
     */
    public static Result transform(FlowNode node, List<Map<String, Object>> sampleRows)
            throws SQLException, java.io.IOException {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        List<String> columns = columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");

        File db = DuckDbUtil.tempDbFile("preview_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            seed(conn, columns, sampleRows);
            List<RowShaper.Relation> produced = RowShaper.shape(conn, node, INPUT, "preview_" + node.id());
            List<RelationPreview> out = new ArrayList<>();
            for (RowShaper.Relation r : produced) out.add(readRelation(conn, r));
            return new Result(columns, out);
        } finally {
            DuckDbUtil.deleteTempDb(db);   // throwaway scratch DB
        }
    }

    /** The ordered union of keys across the sample rows — the preview table's columns. */
    private static List<String> columnsOf(List<Map<String, Object>> rows) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        return new ArrayList<>(cols);
    }

    /** Create the input table (all VARCHAR) and insert the sample rows. */
    private static void seed(Connection conn, List<String> columns, List<Map<String, Object>> rows) throws SQLException {
        StringBuilder create = new StringBuilder("CREATE TABLE ").append(q(INPUT)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) create.append(", ");
            create.append(q(columns.get(i))).append(" VARCHAR");
        }
        create.append(")");
        try (Statement st = conn.createStatement()) {
            st.execute(create.toString());
        }
        StringBuilder ins = new StringBuilder("INSERT INTO ").append(q(INPUT)).append(" VALUES (");
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

    /** Read a produced relation's rows back (capped at {@link #MAX_ROWS}) as ordered column→value maps. */
    private static RelationPreview readRelation(Connection conn, RowShaper.Relation r) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int total = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + q(r.table()))) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) {
                total++;
                if (rows.size() >= MAX_ROWS) continue;   // count all, materialise the first MAX_ROWS
                Map<String, Object> m = new LinkedHashMap<>();
                for (int c = 1; c <= n; c++) m.put(md.getColumnLabel(c), rs.getObject(c));
                rows.add(m);
            }
        }
        return new RelationPreview(r.rel(), total, rows);
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
