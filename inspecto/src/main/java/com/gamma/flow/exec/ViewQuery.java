package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.flow.ViewDefinition;
import com.gamma.sql.SqlSandbox;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * <b>T32 Phase C follow-up — the read side of a {@code sink.view}.</b> A {@link ViewDefinition} records the
 * SQL that derives a logical {@code sink.view} store (when expressible as a single statement). This helper is
 * the <em>consumer</em>: it runs that {@link ViewDefinition#derivedSql() derived SQL} and returns a bounded
 * result, so a job / KPI / report / alert API (or the UI) can bind to the view without re-running the whole
 * producing flow.
 *
 * <h3>Why the sandbox is opened but not sealed</h3>
 * The derived SQL is <em>engine-generated</em> (built by {@link FlowJobRunner#deriveViewSql} from an authored,
 * validated flow) and embeds an absolute {@code read_parquet('<dataDir>/<store>/**')} glob over the
 * source store's at-rest data. It therefore needs file access, so {@link SqlSandbox#seal()} (which blocks all
 * file reads) is deliberately <em>not</em> applied. {@link SqlSandbox#open} still gives the protections that
 * matter here — extension auto-install/-load are off and the memory / thread / query-timeout caps apply — and
 * the only caller-supplied inputs are a validated view name and a numeric row cap, so there is no SQL-injection
 * surface. (Contrast the {@code kpi-to-sql} oracle, which runs <em>untrusted</em> LLM SQL and must seal.)
 */
@PublicApi(since = "4.3.0")
public final class ViewQuery {

    /** A bounded view result: the column order, the rows (≤ {@code cap}), and whether more rows existed. */
    public record Result(List<String> columns, List<Map<String, Object>> rows, int rowCount, boolean capped) {}

    private static final String VIEW = "__inspecto_view";

    private ViewQuery() {}

    /**
     * Run {@code def}'s {@link ViewDefinition#derivedSql() derived SQL} and return up to {@code cap} rows.
     *
     * @throws IllegalStateException if the definition has no derived SQL (a multi-statement view — re-run its
     *                               {@link ViewDefinition#flow() flow} to concretise it instead)
     * @throws SQLException          if the query fails (e.g. the source store's data is missing/unreadable)
     * @throws IOException           if the sandbox temp DB cannot be created
     */
    public static Result run(ViewDefinition def, int cap) throws SQLException, IOException {
        String sql = def.derivedSql();
        if (sql == null || sql.isBlank())
            throw new IllegalStateException(
                    "view '" + def.store() + "' has no derived_sql; re-run flow '" + def.flow() + "' to concretise it");
        int limit = Math.max(0, cap);
        try (SqlSandbox sandbox = SqlSandbox.open(SqlSandboxPolicy.defaultPolicy())) {
            // Intentionally NOT sealed — the derived SQL reads the source store's parquet by absolute path.
            Connection conn = sandbox.connection();
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE VIEW " + ScratchTables.q(VIEW) + " AS " + sql);
            }
            List<String> columns = ScratchTables.columnNames(conn, VIEW);
            // Read one past the cap to report whether the result was truncated.
            List<Map<String, Object>> rows = ScratchTables.readRows(conn, VIEW, limit + 1);
            boolean capped = rows.size() > limit;
            if (capped) rows = rows.subList(0, limit);
            return new Result(columns, rows, rows.size(), capped);
        }
    }
}
