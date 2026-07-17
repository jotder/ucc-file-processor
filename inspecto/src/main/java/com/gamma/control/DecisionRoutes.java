package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.ConditionTree;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decision Rule routes ({@code /decision-rules*}) — the business-logic/routing third of the Rules
 * triad (distinct from Expectation = data-quality and Alert Rule = alerting; {@code docs/GLOSSARY.md}
 * §Decision Rule). Authored objects (full CRUD), persisted as {@code decision-rule} components under
 * {@code <write-root>/registry} exactly like {@link ExpectationRoutes} — same store, same fail-closed
 * gates (503 no write root, 422 bad body, 409 duplicate create, 404 unknown).
 *
 * <p><b>Scope of this cut.</b> CRUD + {@code apply} mirror the mock reference implementation
 * ({@code decision-rules.handler.ts} / {@code decision.ts}, which this replaces). {@code simulate}
 * goes beyond the mock (which still returns canned demo counts): it evaluates the rule's {@code when}
 * condition tree over a caller-supplied {@code sampleRows} batch via
 * {@link com.gamma.query.ConditionTree} (the same semantics the authoring UI previews offline) and
 * returns the real {@code matched}/{@code total} counts. A request with no {@code sampleRows} yields
 * {@code 0/0} — there is no ambient record source for a rule whose target is a pipeline/job, so the
 * sample is the row source (see {@code docs/okf/backend/control-plane/decision-rules.md}).
 * {@code apply} executes each consequence against the platform primitive that already exists —
 * {@code emit-signal} onto this space's Signal Ledger, {@code start-job} via {@link JobService},
 * {@code trigger-pipeline} via {@code CollectorService#triggerRunAsync} — and emits a descriptive stub
 * signal for the remaining platform actions ({@code create-alert}, {@code render-widget},
 * {@code generate-report}, {@code invoke-api}), matching the mock's own scope; the routing actions
 * ({@code route}/{@code tag}/{@code quarantine}/{@code drop}) are record-level — {@code simulate}
 * now counts the rows they would affect, but applying them takes effect only once wired into live
 * pipeline execution (a separate, still-open piece of work).
 */
final class DecisionRoutes implements RouteModule {

    private static final String TYPE = "decision-rule";

    @Override
    public void register(ApiContext api) {
        api.get("/decision-rules", (e, m) -> list(api));
        api.post("/decision-rules", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> create(api, api.body(e))));
        api.put("/decision-rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> update(api, ApiContext.name(m), api.body(e))));
        api.delete("/decision-rules/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> delete(api, ApiContext.name(m))));
        api.post("/decision-rules/([^/]+)/simulate", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> simulate(api, ApiContext.name(m), api.body(e))));
        api.post("/decision-rules/([^/]+)/apply", ApiContext.withCapability("canOperateRuns",
                (e, m) -> apply(api, ApiContext.name(m))));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        if (root == null) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(ComponentRegistry.Component::content)
                .sorted(Comparator.comparingInt(DecisionRoutes::priorityOf)
                        .thenComparing(c -> String.valueOf(c.get("name"))))
                .toList();
    }

    private Object create(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> rule = normalize(body);
        String name = requireName(rule);
        if (store.exists(TYPE, name))
            throw new ApiException(409, "decision rule '" + name + "' already exists (use PUT to update)");
        long now = System.currentTimeMillis();
        rule.put("lastSimulation", null);
        rule.put("createdAt", now);
        rule.put("updatedAt", now);
        return write(store, name, rule);
    }

    private Object update(ApiContext api, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> prev = existing(store, name);
        Map<String, Object> rule = normalize(body);
        rule.put("name", name);
        rule.put("lastSimulation", prev.get("lastSimulation"));
        rule.put("createdAt", prev.getOrDefault("createdAt", System.currentTimeMillis()));
        rule.put("updatedAt", System.currentTimeMillis());
        return write(store, name, rule);
    }

    private Object delete(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        existing(store, name);   // 404 if absent
        store.delete(TYPE, name);
        return Map.of("deleted", name);
    }

    // ── simulate / apply ─────────────────────────────────────────────────────────

    /** Dry-run preview (see class doc): evaluate the rule's {@code when} tree over the request's
     *  {@code sampleRows} via {@link ConditionTree} and stamp the real {@code matched}/{@code total}.
     *  No sample ⇒ {@code 0/0}. Not an authoring edit (MET-5 parity — no version archived). */
    private Object simulate(ApiContext api, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> rule = existing(store, name);
        List<Map<String, Object>> sample = ApiContext.sampleRows(body);
        Map<String, Object> sim = new LinkedHashMap<>();
        sim.put("matched", ConditionTree.matched(rule.get("when"), sample));
        sim.put("total", sample.size());
        sim.put("checkedAt", System.currentTimeMillis());
        Map<String, Object> next = new LinkedHashMap<>(rule);
        next.put("lastSimulation", sim);
        next.put("updatedAt", System.currentTimeMillis());
        store.write(TYPE, name, next, false);   // a simulation stamp isn't an authoring edit (MET-5 parity)
        return next;
    }

    /** Execute every consequence through whichever real platform primitive exists (see class doc for
     *  per-action mapping); never side-effects the rule's stored content. */
    @SuppressWarnings("unchecked")
    private Object apply(ApiContext api, String name) throws IOException {
        Map<String, Object> rule = existing(store(api), name);
        List<Map<String, Object>> consequences = (List<Map<String, Object>>) (List<?>)
                (rule.get("consequences") instanceof List<?> l ? l : List.of());
        List<Map<String, Object>> executed = new ArrayList<>();
        for (Map<String, Object> c : consequences) executed.add(executeOne(api, name, c));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rule", name);
        result.put("executed", executed);
        return result;
    }

    private Map<String, Object> executeOne(ApiContext api, String ruleName, Map<String, Object> c) {
        String action = String.valueOf(c.get("action"));
        String status = "skipped";
        String detail;
        switch (action) {
            case "emit-signal" -> {
                String type = paramStr(c, "type", "decision-rule." + ruleName);
                emitSignal(type, "decision-rule:" + ruleName, Map.of("rule", ruleName));
                status = "executed";
                detail = "emitted signal '" + type + "'";
            }
            case "create-alert" -> {
                String alertName = paramStr(c, "rule", ruleName);
                emitSignal("decision-rule.create-alert", "decision-rule:" + ruleName, Map.of("alert", alertName));
                status = "executed";
                detail = "recorded create-alert stub for '" + alertName + "' (alert authoring is out of scope here — see AlertRoutes)";
            }
            case "start-job" -> {
                String jobId = targetId(c);
                JobService svc = api.service().jobService().orElse(null);
                if (jobId != null && svc != null
                        && svc.triggerRun(jobId, "decision-rule:" + ruleName, Map.of()).isPresent()) {
                    status = "executed";
                    detail = "triggered job '" + jobId + "'";
                } else {
                    detail = "no such job '" + jobId + "'";
                }
            }
            case "trigger-pipeline" -> {
                String pipelineId = targetId(c);
                if (pipelineId != null && api.service().triggerRunAsync(pipelineId).isPresent()) {
                    status = "executed";
                    detail = "triggered pipeline '" + pipelineId + "'";
                } else {
                    detail = "no such pipeline '" + pipelineId + "'";
                }
            }
            case "render-widget", "generate-report", "invoke-api" -> {
                emitSignal("decision-rule." + action, "decision-rule:" + ruleName, Map.of("action", action));
                status = "executed";
                detail = "recorded " + action + " stub signal (execution engine not built yet)";
            }
            case "route", "tag", "quarantine", "drop" ->
                    detail = "routing action — record-level, no immediate side effect";
            default -> detail = "unknown action '" + action + "'";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        out.put("status", status);
        out.put("detail", detail);
        return out;
    }

    private static String targetId(Map<String, Object> c) {
        return c.get("target") instanceof Map<?, ?> t && t.get("id") != null ? String.valueOf(t.get("id")) : null;
    }

    @SuppressWarnings("unchecked")
    private static String paramStr(Map<String, Object> c, String key, String fallback) {
        if (c.get("params") instanceof Map<?, ?> p && p.get(key) != null) return String.valueOf(p.get(key));
        return fallback;
    }

    private static void emitSignal(String type, String source, Map<String, Object> payload) {
        EventLog el = EventLog.current();
        if (el == null) return;
        el.emit(new Signal(UUID.randomUUID().toString(), type, Instant.now(), source, null, Severity.INFO,
                payload).toEvent());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "decision rule").resolve("registry"));
    }

    private static String requireName(Map<String, Object> rule) {
        Object n = rule.get("name");
        if (n == null || String.valueOf(n).isBlank())
            throw new ApiException(422, "decision rule requires a 'name'");
        return String.valueOf(n);
    }

    /** Validate + default a rule body (mirrors the mock's {@code normalize()} exactly, for parity):
     *  {@code targetType} clamped to pipeline|job (default pipeline), {@code consequences} required
     *  non-empty, {@code priority} default 100, {@code enabled} default true, {@code when} default an
     *  empty AND-group in the canonical {@code query-types} shape ({@code kind/op/items}) so a rule
     *  with no filter reads identically to what the UI authors and {@link ConditionTree} evaluates. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalize(Map<String, Object> body) {
        if (!(body.get("consequences") instanceof List<?> cs) || cs.isEmpty())
            throw new ApiException(422, "decision rule requires at least one consequence");
        Map<String, Object> rule = new LinkedHashMap<>(body);
        String targetType = String.valueOf(rule.getOrDefault("targetType", "pipeline"));
        rule.put("targetType", "job".equals(targetType) ? "job" : "pipeline");
        rule.putIfAbsent("description", "");
        rule.putIfAbsent("target", "");
        rule.putIfAbsent("when", Map.of("kind", "group", "op", "AND", "items", List.of()));
        Object priority = rule.get("priority");
        rule.put("priority", priority instanceof Number num ? num.intValue() : 100);
        rule.put("enabled", !"false".equalsIgnoreCase(String.valueOf(rule.getOrDefault("enabled", true))));
        return rule;
    }

    private static Map<String, Object> existing(ComponentStore store, String name) {
        return store.get(TYPE, name).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "decision rule '" + name + "' not found"));
    }

    private static Object write(ComponentStore store, String name, Map<String, Object> content) throws IOException {
        try {
            ComponentRegistry.Component c = store.write(TYPE, name, content);
            return c.content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static int priorityOf(Map<String, Object> c) {
        return c.get("priority") instanceof Number n ? n.intValue() : 100;
    }
}
