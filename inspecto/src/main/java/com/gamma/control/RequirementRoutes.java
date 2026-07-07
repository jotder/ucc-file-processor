package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Requirements intake ({@code /requirements*}, UI-6 + SEC-7(c)) — the backend for the Business→Builder
 * Requirement lifecycle the UI authored mock-first: Business <b>submits</b> (open — raising a requirement
 * needs no special capability, matching the Lens design), a Builder <b>triages</b> (accept/reject) and
 * later marks an accepted requirement <b>delivered</b>. Requirements persist as {@code requirement}
 * components under {@code <write-root>/registry}.
 *
 * <p>SEC-7(c): the triage + deliver transitions are gated <b>server-side</b> on {@code canTriageRequirements}
 * (a no-op on Personal — no Subject is attached); submission and listing are open. The prior mock routed
 * every write through the generic {@code canAuthorWorkbench} component CRUD, which would have both blocked
 * Business submission and left triage unenforced on the backend.
 *
 * <p>Fail-closed: write root unset → 503; unknown kind / bad body → 422; duplicate submit → 409; unknown
 * requirement → 404; an out-of-lifecycle transition (decide a non-{@code submitted}, deliver a
 * non-{@code accepted}) → 409.
 */
final class RequirementRoutes implements RouteModule {

    private static final String TYPE = "requirement";
    private static final Set<String> KINDS = Set.of("kpi", "report", "reconciliation", "rule");

    @Override
    public void register(ApiContext api) {
        api.get("/requirements", (e, m) -> list(api));
        api.post("/requirements", (e, m) -> submit(api, api.body(e)));
        api.post("/requirements/([^/]+)/decision", ApiContext.withCapability("canTriageRequirements",
                (e, m) -> decide(api, ApiContext.name(m), api.body(e))));
        api.post("/requirements/([^/]+)/deliver", ApiContext.withCapability("canTriageRequirements",
                (e, m) -> deliver(api, ApiContext.name(m), api.body(e))));
    }

    private Object list(ApiContext api) {
        Path root = api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
        if (root == null) return List.of();
        return new ComponentStore(root).list(TYPE).stream()
                .map(c -> view(c.content()))
                .sorted(Comparator.comparing(v -> String.valueOf(v.getOrDefault("submittedAt", ""))))
                .toList();
    }

    private Object submit(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        String id = ApiContext.str(body, "id");
        if (id == null) id = ApiContext.str(body, "name");
        if (id == null) throw new ApiException(422, "requirement 'id' is required");
        String title = ApiContext.str(body, "title");
        if (title == null) throw new ApiException(422, "requirement 'title' is required");
        String kind = ApiContext.str(body, "kind");
        if (kind == null || !KINDS.contains(kind.toLowerCase())) throw new ApiException(422, "requirement 'kind' must be one of " + KINDS);
        if (store.exists(TYPE, id))
            throw new ApiException(409, "requirement '" + id + "' already exists");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", title);
        content.put("kind", kind.toLowerCase());
        content.put("description", ApiContext.str(body, "description") == null ? "" : body.get("description"));
        content.put("status", "submitted");
        content.put("submittedAt", Instant.now().toString());
        return write(store, id, content);
    }

    private Object decide(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> content = existing(store, id);
        if (!"submitted".equals(content.get("status")))
            throw new ApiException(409, "requirement '" + id + "' is not awaiting a decision (status "
                    + content.get("status") + ")");
        boolean accept = Boolean.parseBoolean(String.valueOf(body.get("accept")))
                || Boolean.TRUE.equals(body.get("accept"));
        content.put("status", accept ? "accepted" : "rejected");
        content.put("decisionNote", ApiContext.str(body, "note"));
        content.put("decidedAt", Instant.now().toString());
        return write(store, id, content);
    }

    private Object deliver(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = store(api);
        Map<String, Object> content = existing(store, id);
        if (!"accepted".equals(content.get("status")))
            throw new ApiException(409, "only an accepted requirement can be delivered (status "
                    + content.get("status") + ")");
        content.put("status", "delivered");
        content.put("deliveredNote", ApiContext.str(body, "note"));
        content.put("deliveredAt", Instant.now().toString());
        return write(store, id, content);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** The API JSON view: the stored content with the component's in-file {@code name} surfaced as {@code id}. */
    private static Map<String, Object> view(Map<String, Object> content) {
        Map<String, Object> v = new LinkedHashMap<>(content);
        v.put("id", content.get("name"));
        v.remove("name");
        return v;
    }

    private ComponentStore store(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "requirement").resolve("registry"));
    }

    private static Map<String, Object> existing(ComponentStore store, String id) {
        return store.get(TYPE, id).map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "requirement '" + id + "' not found"));
    }

    private static Object write(ComponentStore store, String id, Map<String, Object> content) throws IOException {
        try {
            return view(store.write(TYPE, id, content).content());
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }
}
