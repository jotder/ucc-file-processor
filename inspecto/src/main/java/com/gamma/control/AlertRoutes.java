package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Alert execution engine routes ({@code /alerts*}, v4.1 B5): read-only listings of recent alerts and
 * the loaded rules, a manual evaluation sweep, and (SHOULD, alert-rule authoring) the CRUD writes that
 * arm/disarm rules at runtime. The engine itself is event-driven off the batch bus and lives in the
 * lean core (no agent required).
 *
 * <p>Alert Rules are authored objects persisted as {@code alert-rule} components under
 * {@code <write-root>/registry} (2026-07-18 — promoted off raw {@code *_alert.toon} files onto the
 * same {@link ComponentStore} CRUD contract {@link ExpectationRoutes}/{@code DecisionRoutes} already
 * use): write root unset → 503; an invalid rule body → 422; a duplicate create → 409; an unknown rule
 * on update/delete → 404. A create/update persists the component <em>and</em> arms the rule in the
 * running {@link AlertService} so {@code GET /alerts/rules} and evaluation reflect it immediately —
 * the in-memory list stays the evaluation-time source of truth (cheap per-batch reads), the
 * ComponentStore is what a restart re-arms from. CRUD requires {@code canAuthorAlertRules} (a no-op
 * on Personal).
 */
final class AlertRoutes implements RouteModule {

    private static final String TYPE = "alert-rule";

    @Override
    public void register(ApiContext api) {
        api.get("/alerts", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.recent(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50)))
                .orElse(java.util.List.of()));
        api.get("/alerts/rules", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.rules())
                .orElse(java.util.List.of()));
        api.post("/alerts/evaluate", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.evaluateAll())
                .orElseThrow(() -> new ApiException(503,
                        "alert engine not armed (no alert-rule components loaded)")));
        api.post("/alerts/rules", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> single(e, create(api, api.body(e)))));
        api.put("/alerts/rules/([^/]+)", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> single(e, update(api, ApiContext.name(m), api.body(e)))));
        api.delete("/alerts/rules/([^/]+)", ApiContext.withCapability("canAuthorAlertRules",
                (e, m) -> delete(api, ApiContext.name(m))));
    }

    /** An alert rule's only verbs are the alert-authoring family — declare the applicable set (SEC-7b). */
    private static Object single(HttpExchange e, Object result) {
        ApiContext.resourcePermissions(e, Set.of("canAuthorAlertRules"));
        return result;
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    private Object create(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        AlertRule rule = parse(body);                                   // 422 on an invalid rule
        if (exists(store, rule.name()))
            throw new ApiException(409, "alert rule '" + rule.name() + "' already exists (use PUT to update)");
        Map<String, Object> content = write(store, rule.name(), rule.toMap());
        alerts(api).upsert(rule);                                       // arm in the running engine
        return content;
    }

    private Object update(ApiContext api, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        existing(store, name);   // 404 if absent
        // The name is the storage key — immutable on update. Bind it from the path, not the body, so a
        // stale/edited body name can never fork the component or the in-memory rule.
        Map<String, Object> patched = new java.util.LinkedHashMap<>(body);
        patched.put("name", name);
        AlertRule rule = parse(patched);
        Map<String, Object> content = write(store, name, rule.toMap());
        alerts(api).upsert(rule);
        return content;
    }

    private Object delete(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        existing(store, name);   // 404 if absent
        store.delete(TYPE, name);
        alerts(api).remove(name);
        return Map.of("deleted", name);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "alert rule write").resolve("registry"));
    }

    /** {@code store.exists}, mapping an unsafe name (e.g. containing {@code ..}) to 422 rather than
     *  letting {@link IllegalArgumentException} escape to the generic 500 handler. */
    private static boolean exists(ComponentStore store, String name) {
        try {
            return store.exists(TYPE, name);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> existing(ComponentStore store, String name) {
        try {
            return store.get(TYPE, name).map(ComponentRegistry.Component::content)
                    .orElseThrow(() -> new ApiException(404, "alert rule '" + name + "' not found"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> write(ComponentStore store, String name, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(TYPE, name, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static AlertService alerts(ApiContext api) {
        return api.service().alertService()
                .orElseThrow(() -> new ApiException(503, "alert engine unavailable"));
    }

    private static AlertRule parse(Map<String, Object> body) {
        try {
            return AlertRule.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }
}
