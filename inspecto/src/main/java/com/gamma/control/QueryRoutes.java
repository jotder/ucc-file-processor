package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.Parameters;
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
 * Query execution ({@code POST /queries/{id}/run}; W4, design §6.2) — the "Query &amp; Results" bounded
 * context. Loads a persisted {@code query} component, resolves its {@code $}-parameters server-side
 * ({@link Parameters}), safety-checks the resulting SQL ({@link SqlGuard}), resolves its dataset to a
 * relation ({@link DatasetRelation}), runs it in an ephemeral DuckDB sandbox ({@link QueryExecutor}),
 * and returns the full Result Set contract (typed columns + roles + cardinality, rows, statistics,
 * candidate renderings, export options). Query authoring reuses the generic component CRUD
 * ({@code /components/query/{id}}); this module owns only the {@code /run} capability.
 *
 * <p>Fail-closed: write root unset → 503; unknown query/dataset → 404; a non-{@code sql} query, an
 * empty text, a bad parameter, an unsafe column, or SQL that fails the {@link SqlGuard} allow-list or
 * DuckDB → 422. Results are transactional and never cached (no ETag).
 */
final class QueryRoutes implements RouteModule {

    private static final int DEFAULT_LIMIT = 500;
    private static final int MAX_LIMIT = 10_000;

    @Override
    public void register(ApiContext api) {
        api.post("/queries/([^/]+)/run", (e, m) -> runQuery(api, e, ApiContext.name(m), api.body(e)));
    }

    private Object runQuery(ApiContext api, HttpExchange ex, String id, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "query execution");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));

        Map<String, Object> query = component(store, "query", id)
                .orElseThrow(() -> new ApiException(404, "no query '" + id + "'"));

        String type = ApiContext.str(query, "type");
        if (type != null && !"sql".equalsIgnoreCase(type))
            throw new ApiException(422, "only type:sql queries run server-side today (got '" + type
                    + "'); author structured queries as SQL");
        String text = ApiContext.str(query, "text");
        if (text == null) throw new ApiException(422, "query '" + id + "' has no 'text'");

        // 1. Resolve $-parameters (declared defaults + caller values + session/clock context) → SQL literals.
        String resolved;
        try {
            resolved = Parameters.resolve(text, declaredParams(query), callerValues(body),
                    Parameters.Context.of(ApiContext.actor(ex), null));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }

        // 2. Safety-gate the resolved query text (single read-only SELECT, no file/extension surface).
        List<Finding> findings = SqlGuard.check(resolved);
        if (!findings.isEmpty())
            return ApiContext.respondJson(ex, 422, Map.of(
                    "error", "query failed the SQL safety check", "findings", findings));

        // 3. Resolve the dataset (if any) to a trusted relation SQL registered as a view.
        String datasetId = ApiContext.str(query, "datasetId");
        String relationSql = null;
        if (datasetId != null) {
            Map<String, Object> dataset = component(store, "dataset", datasetId)
                    .orElseThrow(() -> new ApiException(404, "query '" + id + "' references unknown dataset '" + datasetId + "'"));
            try {
                relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
            } catch (IllegalArgumentException bad) {
                throw new ApiException(422, bad.getMessage());
            }
        }

        // 4. Execute with pagination / sort / projection.
        QueryExecutor.Request req = new QueryExecutor.Request(datasetId, relationSql, resolved,
                limit(body), offset(body), projection(body), sort(body));
        QueryExecutor.Result result;
        try {
            result = QueryExecutor.run(req);
        } catch (IllegalArgumentException bad) {          // unsafe projection/sort identifier
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException sql) {
            throw new ApiException(422, "query failed: " + sql.getMessage());
        } catch (IOException io) {
            throw new ApiException(503, "query sandbox unavailable: " + io.getMessage());
        }

        return response(result);
    }

    // ── response shaping (design §6.2) ────────────────────────────────────────────

    private static Object response(QueryExecutor.Result r) {
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
        data.put("renderings", ResultSetDescriptor.renderings(r.columns()));
        data.put("exportOptions", List.of("csv", "parquet"));
        return data;
    }

    // ── request parsing ────────────────────────────────────────────────────────────

    private static java.util.Optional<Map<String, Object>> component(ComponentStore store, String type, String id) {
        try {
            return store.get(type, id).map(ComponentRegistry.Component::content);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private static List<Parameters.Def> declaredParams(Map<String, Object> query) {
        List<Parameters.Def> defs = new ArrayList<>();
        if (query.get("parameters") instanceof List<?> ps)
            for (Object o : ps)
                if (o instanceof Map<?, ?> pm)
                    defs.add(new Parameters.Def(mapStr(pm, "name"), mapStr(pm, "type"), mapStr(pm, "default")));
        return defs;
    }

    private static Map<String, String> callerValues(Map<String, Object> body) {
        Map<String, String> values = new LinkedHashMap<>();
        if (body.get("parameters") instanceof Map<?, ?> pv)
            pv.forEach((k, v) -> { if (v != null) values.put(String.valueOf(k), String.valueOf(v)); });
        return values;
    }

    private static int limit(Map<String, Object> body) {
        int l = intOr(body.get("limit"), DEFAULT_LIMIT);
        return Math.max(1, Math.min(MAX_LIMIT, l));
    }

    private static int offset(Map<String, Object> body) {
        return Math.max(0, intOr(body.get("offset"), 0));
    }

    private static List<String> projection(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        if (body.get("projection") instanceof List<?> cols)
            for (Object c : cols) if (c != null) out.add(String.valueOf(c));
        return out;
    }

    private static List<QueryExecutor.Sort> sort(Map<String, Object> body) {
        List<QueryExecutor.Sort> out = new ArrayList<>();
        if (body.get("sort") instanceof List<?> items)
            for (Object o : items)
                if (o instanceof Map<?, ?> s) {
                    String field = mapStr(s, "field");
                    if (field != null)
                        out.add(new QueryExecutor.Sort(field, "desc".equalsIgnoreCase(mapStr(s, "dir"))));
                }
        return out;
    }

    private static String mapStr(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString().trim();
    }

    private static int intOr(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }
}
