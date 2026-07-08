package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlGuard;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The semantic / headless BI API (BI-7): spec-based aggregation over Datasets without authoring a
 * {@code query} component — the backend twin of the UI viz layer's QuerySpec seam, so an external
 * consumer (or the UI itself, later) can evaluate measures server-side over the at-rest data.
 *
 * <ul>
 *   <li>{@code GET /bi/datasets} — the queryable Dataset components (id + binding summary).</li>
 *   <li>{@code POST /bi/query} — {@code {dataset, measures[{agg,field}], groupBy[], filters[],
 *       orderBy[], limit}} → compiled by {@link MeasureCompiler} (validated identifiers + typed
 *       literals only), SqlGuard-checked, executed in the same ephemeral DuckDB sandbox as
 *       {@code /queries/{id}/run}, returning the same Result Set contract.</li>
 * </ul>
 *
 * <p>Fail-closed like {@link QueryRoutes}: write root unset → 503; unknown dataset → 404; a bad
 * spec, unsafe identifier, or guard/DuckDB failure → 422.
 */
final class BiRoutes implements RouteModule {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 10_000;

    @Override
    public void register(ApiContext api) {
        api.get("/bi/datasets", (e, m) -> listDatasets(api));
        api.post("/bi/query", (e, m) -> biQuery(api, e, api.body(e)));
        // BI-8 template gallery: curated starter widget/dashboard sets, parameterized by dataset and
        // applied server-side into the component store (cross-space sharing stays BundleRoutes' job).
        api.get("/bi/templates", (e, m) -> BiTemplates.list());
        api.post("/bi/templates/([^/]+)/apply", ApiContext.withCapability("canAuthorWorkbench", (e, m) ->
                BiTemplates.apply(api, ApiContext.name(m), api.body(e))));
    }

    /** {@code GET /bi/datasets} — every persisted dataset component with its binding kind. */
    private Object listDatasets(ApiContext api) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "BI dataset listing");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ComponentRegistry.Component c : store.list("dataset")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.name());
            Map<String, Object> cfg = c.content();
            m.put("binding", cfg.containsKey("view") ? "view" : cfg.containsKey("physicalRef") ? "physicalRef" : "unbound");
            Object label = cfg.get("label");
            if (label != null) m.put("label", label);
            out.add(m);
        }
        return out;
    }

    /** {@code POST /bi/query} — compile + execute one measure spec (see class doc). */
    private Object biQuery(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "BI query execution");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        // 1. Parse + compile the spec (validated identifiers / typed literals only).
        MeasureCompiler.Spec spec;
        String sql;
        try {
            spec = MeasureCompiler.parse(body, DEFAULT_LIMIT, MAX_LIMIT);
            sql = MeasureCompiler.compile(spec);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }

        // 2. Defence in depth: the compiled text still passes the same guard as caller-authored SQL.
        List<Finding> findings = SqlGuard.check(sql);
        if (!findings.isEmpty())
            return ApiContext.respondJson(ex, 422, Map.of(
                    "error", "compiled BI query failed the SQL safety check", "findings", findings));

        // 3. Resolve the dataset to its trusted relation and execute in the sandbox.
        Map<String, Object> dataset = store.get("dataset", spec.dataset())
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "no dataset '" + spec.dataset() + "'"));
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }

        QueryExecutor.Request req = new QueryExecutor.Request(spec.dataset(), relationSql, sql,
                spec.limit(), 0, List.of(), List.of());
        try {
            return response(QueryExecutor.run(req), sql);
        } catch (SQLException e) {
            throw new ApiException(422, "BI query failed: " + e.getMessage());
        }
    }

    /** The Result Set contract (same shape as {@code /queries/{id}/run}) + the compiled SQL for transparency. */
    private static Object response(QueryExecutor.Result r, String sql) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ResultSetDescriptor.Column c : r.columns()) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name());
            col.put("type", c.type());
            col.put("role", c.role());
            col.put("cardinality", c.cardinality());
            columns.add(col);
        }
        Map<String, Object> resultSet = new LinkedHashMap<>();
        resultSet.put("columns", columns);
        resultSet.put("rowCount", r.rowCount());

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", r.rowCount());
        statistics.put("elapsedMs", r.elapsedMs());
        statistics.put("truncated", r.truncated());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultSet", resultSet);
        data.put("rows", r.rows());
        data.put("statistics", statistics);
        data.put("sql", sql);
        return data;
    }
}
