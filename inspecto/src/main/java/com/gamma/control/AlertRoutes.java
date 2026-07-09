package com.gamma.control;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.config.io.ConfigCodec;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Alert execution engine routes ({@code /alerts*}, v4.1 B5): read-only listings of recent alerts and
 * the loaded {@code *_alert.toon} rules, a manual evaluation sweep, and (SHOULD, alert-rule authoring)
 * the CRUD writes that arm/disarm rules at runtime. The engine itself is event-driven off the batch bus
 * and lives in the lean core (no agent required).
 *
 * <p>Writes are the fail-closed sibling of {@link ExpectationRoutes} in the Rules triad: write root
 * unset → 503; an invalid rule body → 422; a name unusable as a filename → 422; a duplicate create → 409;
 * an unknown rule on update/delete → 404. A create/update writes {@code <name>_alert.toon} atomically
 * under the write root (the same suffix {@code ServiceBootstrap} re-arms on the next boot) <em>and</em>
 * arms the rule in the running {@link AlertService} so {@code GET /alerts/rules} and evaluation reflect it
 * immediately. CRUD requires {@code canAuthorAlertRules} (a no-op on Personal).
 */
final class AlertRoutes implements RouteModule {

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
                        "alert engine not armed (no *_alert.toon rules loaded)")));
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
        Path writeRoot = WriteGates.requireWriteRoot(api, "alert rule write");
        AlertRule rule = parse(body);                                   // 422 on an invalid rule
        String fileName = WriteGates.safeName(rule.name(), "alert rule name");
        Path target = WriteGates.jail(writeRoot, writeRoot.resolve(fileName + "_alert.toon"), "resolved path");
        AlertService alerts = alerts(api);
        WriteGates.conflictIf(alerts.has(rule.name()) || Files.exists(target),
                "alert rule '" + rule.name() + "' already exists (use PUT to update)");
        writeRule(target, rule);
        alerts.upsert(rule);                                           // arm in the running engine
        return rule.toMap();
    }

    private Object update(ApiContext api, String name, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "alert rule write");
        AlertService alerts = alerts(api);
        if (!alerts.has(name)) throw new ApiException(404, "alert rule '" + name + "' not found");
        // The name is the storage key — immutable on update. Bind it from the path, not the body, so a
        // stale/edited body name can never fork the file or the in-memory rule.
        Map<String, Object> patched = new java.util.LinkedHashMap<>(body);
        patched.put("name", name);
        AlertRule rule = parse(patched);
        String fileName = WriteGates.safeName(name, "alert rule name");
        Path target = WriteGates.jail(writeRoot, writeRoot.resolve(fileName + "_alert.toon"), "resolved path");
        writeRule(target, rule);
        alerts.upsert(rule);
        return rule.toMap();
    }

    private Object delete(ApiContext api, String name) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "alert rule write");
        AlertService alerts = alerts(api);
        if (!alerts.has(name)) throw new ApiException(404, "alert rule '" + name + "' not found");
        String fileName = WriteGates.safeName(name, "alert rule name");
        Path target = WriteGates.jail(writeRoot, writeRoot.resolve(fileName + "_alert.toon"), "resolved path");
        Files.deleteIfExists(target);
        alerts.remove(name);
        return Map.of("deleted", name);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

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

    /** Encode the rule as a {@code alert { … }} block and write it atomically (temp + move). */
    private static void writeRule(Path target, AlertRule rule) throws IOException {
        byte[] bytes = ConfigCodec.toToon(Map.of("alert", rule.toMap())).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".alert-");
    }
}
