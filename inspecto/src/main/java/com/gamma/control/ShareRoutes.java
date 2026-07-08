package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.sql.SqlGuard;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public / embedded dashboard sharing (BI-6), <b>fail-closed</b>: the whole surface is inert unless
 * {@code -Dbi.share.secret} is configured (≥ 16 chars; rotate it to revoke every issued link).
 *
 * <ul>
 *   <li>{@code POST /dashboards/{name}/share} — authoring-gated; issues an HMAC-signed, expiring token
 *       ({@code ttl_hours}, default 168) for one dashboard. 503 when sharing is disabled.</li>
 *   <li>{@code GET /public/dashboards/{token}} — <b>anonymous</b> (the token is the credential; the
 *       {@code ControlApi} auth gate exempts this prefix): resolves the shared dashboard + the widget
 *       components it references, read-only. Invalid/expired/tampered tokens are an indistinguishable 404.</li>
 *   <li>{@code POST /public/dashboards/{token}/query} — anonymous headless BI query
 *       (same body as {@code /bi/query}) restricted to the datasets the shared dashboard's widgets
 *       actually reference — a token never becomes a general data API.</li>
 * </ul>
 */
final class ShareRoutes implements RouteModule {

    private static final int DEFAULT_TTL_HOURS = 168;   // one week
    private static final int MAX_TTL_HOURS = 24 * 365;

    @Override
    public void register(ApiContext api) {
        api.post("/dashboards/([^/]+)/share", ApiContext.withCapability("canAuthorWorkbench", (e, m) ->
                share(api, ApiContext.name(m), api.body(e))));
        api.get("/public/dashboards/([^/]+)", (e, m) -> resolve(api, ApiContext.name(m)));
        api.post("/public/dashboards/([^/]+)/query", (e, m) ->
                publicQuery(api, e, ApiContext.name(m), api.body(e)));
    }

    /** {@code POST /dashboards/{name}/share} — issue a link token for an existing dashboard. */
    private Object share(ApiContext api, String name, Map<String, Object> body) throws IOException {
        if (!ShareTokens.enabled())
            throw new ApiException(503, "dashboard sharing is disabled; set -Dbi.share.secret (>= 16 chars) to enable");
        ComponentStore store = store(api);
        store.get("dashboard", name).orElseThrow(() -> new ApiException(404, "no dashboard '" + name + "'"));

        long ttlHours = body.get("ttl_hours") instanceof Number n ? n.longValue() : DEFAULT_TTL_HOURS;
        ttlHours = Math.max(1, Math.min(MAX_TTL_HOURS, ttlHours));
        long exp = Instant.now().plusSeconds(ttlHours * 3600).getEpochSecond();
        String token = ShareTokens.issue("dashboard", name, exp)
                .orElseThrow(() -> new ApiException(503, "sharing disabled"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("url", "/public/dashboards/" + token);
        out.put("dashboard", name);
        out.put("expiresAt", Instant.ofEpochSecond(exp).toString());
        return out;
    }

    /** {@code GET /public/dashboards/{token}} — the shared dashboard + its widgets, read-only. */
    private Object resolve(ApiContext api, String token) throws IOException {
        ShareTokens.Scope scope = requireScope(token);
        ComponentStore store = store(api);
        Map<String, Object> dashboard = store.get("dashboard", scope.name())
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "not found"));   // deleted since sharing

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dashboard", Map.of("id", scope.name(), "content", dashboard));
        List<Map<String, Object>> widgets = new ArrayList<>();
        for (String wid : referencedIds(dashboard, Set.of("widget", "widgetId"), "widgets")) {
            store.get("widget", wid).map(ComponentRegistry.Component::content)
                    .ifPresent(w -> widgets.add(Map.of("id", wid, "content", w)));
        }
        out.put("widgets", widgets);
        out.put("expiresAt", Instant.ofEpochSecond(scope.expiresEpochSec()).toString());
        return out;
    }

    /** {@code POST /public/dashboards/{token}/query} — BI query fenced to the dashboard's datasets. */
    private Object publicQuery(ApiContext api, HttpExchange ex, String token, Map<String, Object> body)
            throws IOException {
        ShareTokens.Scope scope = requireScope(token);
        ComponentStore store = store(api);
        Map<String, Object> dashboard = store.get("dashboard", scope.name())
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "not found"));

        MeasureCompiler.Spec spec;
        String sql;
        try {
            spec = MeasureCompiler.parse(body, 500, 10_000);
            sql = MeasureCompiler.compile(spec);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        if (!allowedDatasets(store, dashboard).contains(spec.dataset()))
            throw new ApiException(403, "this share link does not cover dataset '" + spec.dataset() + "'");

        List<Finding> findings = SqlGuard.check(sql);
        if (!findings.isEmpty()) throw new ApiException(422, "compiled query failed the SQL safety check");

        Map<String, Object> dataset = store.get("dataset", spec.dataset())
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "no dataset '" + spec.dataset() + "'"));
        String relationSql;
        try {
            relationSql = DatasetRelation.relationSql(dataset, api.dataRoot(),
                    new ViewStore(api.writeRoot().resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    spec.dataset(), relationSql, sql, spec.limit(), 0, List.of(), List.of()));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", r.rows());
            out.put("rowCount", r.rowCount());
            out.put("truncated", r.truncated());
            return out;
        } catch (SQLException e) {
            throw new ApiException(422, "query failed: " + e.getMessage());
        }
    }

    /** The datasets the dashboard's own widgets reference — the fence for the public query surface. */
    private static Set<String> allowedDatasets(ComponentStore store, Map<String, Object> dashboard) {
        Set<String> allowed = new LinkedHashSet<>(referencedIds(dashboard, Set.of("dataset", "datasetId"), null));
        for (String wid : referencedIds(dashboard, Set.of("widget", "widgetId"), "widgets"))
            store.get("widget", wid).map(ComponentRegistry.Component::content).ifPresent(w ->
                    allowed.addAll(referencedIds(w, Set.of("dataset", "datasetId"), null)));
        return allowed;
    }

    /**
     * Collect referenced component ids from a content tree: every string value under one of {@code keys},
     * plus — when {@code listKey} is given — the members of that list (plain strings, or maps carrying
     * {@code id}/one of {@code keys}). Shape-tolerant on purpose: dashboards are authored UI-side.
     */
    @SuppressWarnings("unchecked")
    private static List<String> referencedIds(Map<String, Object> content, Set<String> keys, String listKey) {
        List<String> out = new ArrayList<>();
        walk(content, keys, listKey, out);
        return out;
    }

    private static void walk(Object node, Set<String> keys, String listKey, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (keys.contains(k) && v instanceof String s && !s.isBlank()) out.add(s.trim());
                else if (k.equals(listKey) && v instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof String s && !s.isBlank()) out.add(s.trim());
                        else if (item instanceof Map<?, ?> m) {
                            Object id = m.get("id");
                            if (id instanceof String s && !s.isBlank()) out.add(s.trim());
                            walk(m, keys, listKey, out);
                        }
                    }
                } else walk(v, keys, listKey, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) walk(item, keys, listKey, out);
        }
    }

    /** Verify the token; every failure mode is the same 404 (no oracle for tamper vs expiry vs unknown). */
    private static ShareTokens.Scope requireScope(String token) {
        return ShareTokens.verify(token)
                .filter(s -> "dashboard".equals(s.type()))
                .orElseThrow(() -> new ApiException(404, "not found"));
    }

    private static ComponentStore store(ApiContext api) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "dashboard sharing");
        return new ComponentStore(writeRoot.resolve("registry"));
    }
}
