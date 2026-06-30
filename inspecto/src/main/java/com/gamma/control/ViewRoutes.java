package com.gamma.control;

import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.pipeline.exec.ViewQuery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only access to the durable {@code sink.view} definitions a flow job records ({@code /views*}):
 * list, one definition, and a bounded data preview. Extracted verbatim from {@link ControlApi}:
 * identical routes, HTTP statuses (400/404/409/422) and row-cap behaviour.
 */
final class ViewRoutes implements RouteModule {

    private static final int DEFAULT_VIEW_ROW_CAP = 1000;
    private static final int MAX_VIEW_ROW_CAP = 10_000;

    @Override
    public void register(ApiContext api) {
        api.get("/views", (e, m) -> viewList(api));
        api.get("/views/([^/]+)", (e, m) -> viewDefinition(api, ApiContext.name(m)));
        api.get("/views/([^/]+)/data", (e, m) -> viewData(api, ApiContext.name(m), ApiContext.query(e, "limit")));
    }

    private Path viewsRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : api.writeRoot().resolve("views");
    }

    /** {@code GET /views} — summaries of every recorded {@code sink.view} definition (empty when no write root). */
    private Object viewList(ApiContext api) {
        Path root = viewsRootOrNull(api);
        if (root == null) return List.of();
        return new ViewStore(root).list().stream().map(ViewRoutes::viewSummary).toList();
    }

    /** {@code GET /views/{name}} — one view's full definition (incl. {@code derived_sql}); 404 if absent. */
    private Object viewDefinition(ApiContext api, String name) {
        ViewDefinition def = requireView(api, name);
        Map<String, Object> m = new LinkedHashMap<>(def.toMap());
        m.put("has_derived_sql", def.derivedSql() != null && !def.derivedSql().isBlank());
        return m;
    }

    /**
     * {@code GET /views/{name}/data?limit=N} — run the view's {@code derived_sql} and return up to {@code N}
     * rows (default {@value #DEFAULT_VIEW_ROW_CAP}, capped at {@value #MAX_VIEW_ROW_CAP}). 404 if the view is
     * absent; 409 if it has no {@code derived_sql} (a multi-statement view — re-run its flow); 422 on a query
     * error (e.g. the source store has no data yet).
     */
    private Object viewData(ApiContext api, String name, String limitParam) {
        ViewDefinition def = requireView(api, name);
        int cap = viewRowCap(limitParam);
        try {
            ViewQuery.Result r = ViewQuery.run(def, cap);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("view", def.store());
            out.put("columns", r.columns());
            out.put("rowCount", r.rowCount());
            out.put("capped", r.capped());
            out.put("rows", r.rows());
            return out;
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "view query failed: " + e.getMessage());
        }
    }

    /** Load a view definition by name, or 404 (400 if the name is unsafe). */
    private ViewDefinition requireView(ApiContext api, String name) {
        Path root = viewsRootOrNull(api);
        ViewDefinition def;
        try {
            def = root == null ? null : new ViewStore(root).get(name).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (def == null) throw new ApiException(404, "no view '" + name + "'");
        return def;
    }

    /** Clamp the {@code ?limit=} param to {@code [0, MAX]}; absent/non-numeric ⇒ the default cap. */
    private static int viewRowCap(String limitParam) {
        if (limitParam == null || limitParam.isBlank()) return DEFAULT_VIEW_ROW_CAP;
        try {
            return Math.max(0, Math.min(MAX_VIEW_ROW_CAP, Integer.parseInt(limitParam.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_VIEW_ROW_CAP;
        }
    }

    private static Map<String, Object> viewSummary(ViewDefinition def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("store", def.store());
        m.put("flow", def.flow());
        m.put("source_store", def.sourceStores());
        m.put("has_derived_sql", def.derivedSql() != null && !def.derivedSql().isBlank());
        m.put("defined_at", def.definedAt());
        return m;
    }
}
