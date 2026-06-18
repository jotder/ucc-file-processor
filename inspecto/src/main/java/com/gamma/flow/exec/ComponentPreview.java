package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.flow.FlowNode;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
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
        List<String> columns = ScratchTables.columnsOf(sampleRows);
        if (columns.isEmpty()) throw new IllegalArgumentException("sample rows have no columns");

        File db = DuckDbUtil.tempDbFile("preview_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, INPUT, columns, sampleRows);
            List<RowShaper.Relation> produced = RowShaper.shape(conn, node, INPUT, "preview_" + node.id());
            List<RelationPreview> out = new ArrayList<>();
            for (RowShaper.Relation r : produced) {
                out.add(new RelationPreview(r.rel(),
                        ScratchTables.count(conn, r.table()),
                        ScratchTables.readRows(conn, r.table(), MAX_ROWS)));
            }
            return new Result(columns, out);
        } finally {
            DuckDbUtil.deleteTempDb(db);   // throwaway scratch DB
        }
    }
}
