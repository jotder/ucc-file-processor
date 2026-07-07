package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLevel;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.expectation.Expectation;
import com.gamma.expectation.ExpectationEvaluator;
import com.gamma.ops.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-quality <b>Expectation</b> engine routes ({@code /expectations*}, ING-6) — the data-quality third
 * of the Rules triad. Expectations are authored objects (full CRUD) persisted as {@code expectation}
 * components under {@code <write-root>/registry}; evaluation counts violating records in the target's
 * at-rest Parquet ({@link ExpectationEvaluator}) and, on failure, opens a correlated Incident (deduped
 * while one is still open) and emits an {@code EXPECTATION_FAILED} signal — the same consequence chain
 * the mock backend drives ({@code expectations.handler.ts}).
 *
 * <p>Fail-closed: write root unset → 503; a bad expectation body / target / column → 422; a duplicate
 * create → 409; an unknown expectation → 404. CRUD writes require {@code canAuthorWorkbench} (a no-op on
 * Personal); evaluation persists {@code lastResult}, so it also needs the write root.
 */
final class ExpectationRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ExpectationRoutes.class);
    private static final String TYPE = "expectation";

    @Override
    public void register(ApiContext api) {
        api.get("/expectations", (e, m) -> list(api));
        api.post("/expectations/evaluate", (e, m) -> evaluateAll(api));
        api.post("/expectations/([^/]+)/evaluate", (e, m) -> evaluateOne(api, ApiContext.name(m)));
        api.post("/expectations", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> create(api, api.body(e))));
        api.put("/expectations/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> update(api, ApiContext.name(m), api.body(e))));
        api.delete("/expectations/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> delete(api, ApiContext.name(m))));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    private Object list(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        if (root == null) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(ComponentRegistry.Component::content)
                .sorted(Comparator.comparing(c -> String.valueOf(c.get("name"))))
                .toList();
    }

    private Object create(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Expectation exp = parse(body);
        if (store.exists(TYPE, exp.name()))
            throw new ApiException(409, "expectation '" + exp.name() + "' already exists (use PUT to update)");
        long now = System.currentTimeMillis();
        Map<String, Object> content = exp.toMap();
        content.put("lastResult", null);
        content.put("createdAt", now);
        content.put("updatedAt", now);
        return write(store, exp.name(), content);
    }

    private Object update(ApiContext api, String name, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> prev = existing(store, name);
        Expectation exp = parse(body);
        Map<String, Object> content = exp.toMap();
        content.put("lastResult", prev.get("lastResult"));                       // preserve last evaluation
        content.put("createdAt", prev.getOrDefault("createdAt", System.currentTimeMillis()));
        content.put("updatedAt", System.currentTimeMillis());
        return write(store, name, content);
    }

    private Object delete(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        existing(store, name);   // 404 if absent
        store.delete(TYPE, name);
        return Map.of("deleted", name);
    }

    // ── evaluation ──────────────────────────────────────────────────────────────

    private Object evaluateAll(ApiContext api) throws IOException {
        ComponentStore store = store(api);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ComponentRegistry.Component c : store.list(TYPE)) {
            Map<String, Object> content = c.content();
            if (!"false".equalsIgnoreCase(String.valueOf(content.getOrDefault("enabled", true))))
                out.add(runAndPersist(api, store, content));
            else
                out.add(content);
        }
        out.sort(Comparator.comparing(c -> String.valueOf(c.get("name"))));
        return out;
    }

    private Object evaluateOne(ApiContext api, String name) throws IOException {
        ComponentStore store = store(api);
        return runAndPersist(api, store, existing(store, name));
    }

    /** Evaluate one expectation, persist its result, fire the failure consequence chain, return the updated content. */
    private Map<String, Object> runAndPersist(ApiContext api, ComponentStore store, Map<String, Object> content)
            throws IOException {
        Expectation exp = parse(content);
        ExpectationEvaluator.Result result;
        try {
            result = ExpectationEvaluator.evaluate(exp, api.dataRoot());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        } catch (SQLException sql) {
            throw new ApiException(422, "expectation evaluation failed: " + sql.getMessage());
        }

        Map<String, Object> lastResult = new LinkedHashMap<>();
        lastResult.put("status", result.status());
        lastResult.put("violations", result.violations());
        lastResult.put("checkedAt", result.checkedAt());

        Map<String, Object> next = new LinkedHashMap<>(content);
        next.put("lastResult", lastResult);
        next.put("updatedAt", result.checkedAt());
        store.write(TYPE, exp.name(), next);

        if ("FAILED".equals(result.status())) raiseIncident(api, exp, result.violations());
        return next;
    }

    /**
     * On a failed evaluation: open a correlated Incident (deduped while one for {@code expectation:<name>}
     * is still open) and emit the {@code EXPECTATION_FAILED} signal that fans out to notification channels.
     * Never disturbs the evaluation response — a persistence hiccup is logged and swallowed.
     */
    private void raiseIncident(ApiContext api, Expectation exp, long violations) {
        String correlationId = "expectation:" + exp.name();
        try {
            boolean open = !api.service().objects().active(ObjectType.INCIDENT, correlationId).isEmpty();
            if (open) return;   // one Incident already tracks this expectation's breach

            String title = "Expectation failed: " + exp.name();
            String description = exp.kind() + " check on " + exp.targetType() + " \"" + exp.target()
                    + "\" column \"" + exp.column() + "\" — " + violations + " violating record(s).";
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("expectation", exp.name());
            attrs.put("kind", exp.kind());
            attrs.put("target", exp.target());
            attrs.put("column", exp.column());
            attrs.put("violations", String.valueOf(violations));
            api.service().objects().open(ObjectType.INCIDENT, title, description, exp.severity(),
                    correlationId, attrs);

            EventLog.current().emit(Event.builder(EventType.EXPECTATION_FAILED)
                    .level("CRITICAL".equals(exp.severity()) ? EventLevel.ERROR : EventLevel.WARN)
                    .source(ExpectationRoutes.class.getName())
                    .correlationId(correlationId)
                    .message(title + " — " + description)
                    .attr("expectation", exp.name())
                    .attr("kind", exp.kind())
                    .attr("violations", violations)
                    .attr("severity", exp.severity()));
        } catch (RuntimeException e) {
            log.warn("could not raise incident for failed expectation {}: {}", exp.name(), e.getMessage());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "expectation").resolve("registry"));
    }

    private static Expectation parse(Map<String, Object> body) {
        try {
            return Expectation.fromMap(body);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static Map<String, Object> existing(ComponentStore store, String name) {
        return store.get(TYPE, name).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "expectation '" + name + "' not found"));
    }

    private static Object write(ComponentStore store, String name, Map<String, Object> content) throws IOException {
        try {
            ComponentRegistry.Component c = store.write(TYPE, name, content);
            return c.content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }
}
