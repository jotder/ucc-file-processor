package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lens access configuration routes ({@code /access/*} — {@code docs/superpower/lens-access-config-design.md},
 * vocabulary {@code docs/GLOSSARY.md} §1-A): the <b>Access Catalog</b> (the tree of menus → panes →
 * capability-bound action nodes, derived and saved by the UI) and one <b>Access Profile</b> per subject
 * (today {@code subjectType: lens} — Builder/Ops/Business visibility shaping; under RBAC the same
 * documents carry {@code subjectType: role} and the security module enforces them server-side).
 *
 * <p>{@code /access/roles} (RBAC R1) serves and authors the role → capability/data-scope table
 * ({@link Roles} — a {@code roles.toon} settings doc overlaying the shipped seed; the security
 * module's {@code RoleMapper} resolves grants through it per request, so edits apply immediately).
 *
 * <p>Persistence is {@link ComponentStore} ({@code access-catalog} singleton id {@value #CATALOG_ID},
 * {@code access-profile} keyed {@code <subjectType>-<subjectId>}) — same fail-closed gates as the other
 * component-backed families (503 no write root, 422 bad body/name, 404 unknown). A grant may reference a
 * node id that is not (yet / any longer) in the saved catalog — shape is validated strictly, id existence
 * deliberately is not, so catalog evolution never invalidates saved profiles.
 */
final class AccessRoutes implements RouteModule {

    private static final String CATALOG_TYPE = "access-catalog";
    private static final String PROFILE_TYPE = "access-profile";
    private static final String CATALOG_ID = "catalog";
    private static final Set<String> SUBJECT_TYPES = Set.of("lens", "role");
    private static final Set<String> NODE_KINDS = Set.of("menu", "pane", "action");
    private static final Set<String> GRANT_VALUES = Set.of("allow", "deny");

    @Override
    public void register(ApiContext api) {
        api.get("/access/roles", (e, m) -> ETags.respond(e, roles(api)));
        api.put("/access/roles", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> saveRoles(api, api.body(e))));
        api.get("/access/policies", (e, m) -> ETags.respond(e, policies(api)));
        api.put("/access/policies", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> savePolicies(api, api.body(e))));
        // "Why denied?" dry-run for the caller's own session (BACKLOG §5). A GET (read action) on
        // purpose: it changes nothing, and a POST would be a 'write' the very policy under test could
        // deny at the route PEP — locking the denied subject out of the tool that explains their denial.
        // Ungated like the /access reads above; on Personal/Standard (no engine) it returns {enabled:false}.
        api.get("/access/explain", (e, m) -> explain(e));
        api.get("/access/catalog", (e, m) -> ETags.respond(e, catalog(api)));
        api.put("/access/catalog", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> saveCatalog(api, api.body(e))));
        api.get("/access/profiles", (e, m) -> ETags.respond(e, profiles(api)));
        api.put("/access/profiles/([^/]+)", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> saveProfile(api, ApiContext.name(m), api.body(e))));
        api.delete("/access/profiles/([^/]+)", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> deleteProfile(api, ApiContext.name(m))));
    }

    // ── roles (RBAC R1 — authorable role → capability/data-scope table) ────────────

    /** The effective table: authored roles overlaid on the seed, each row marked {@code source:
     *  authored|seed}. An unreadable authored doc is surfaced (grants are suspended, fail-closed). */
    private Object roles(ApiContext api) {
        Roles.Doc doc = Roles.load(api.writeRoot());
        Map<String, Object> out = new LinkedHashMap<>();
        if (doc.unreadable()) {
            out.put("roles", List.of());
            out.put("error", "roles.toon is unreadable — all role grants are suspended (fail-closed) until it is fixed or re-saved");
            return out;
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        Roles.effective(api.writeRoot()).forEach((name, def) ->
                rows.add(roleShape(name, def, doc.authored().containsKey(name) ? "authored" : "seed")));
        out.put("roles", rows);
        if (!doc.attributeClaims().isEmpty())
            out.put("identity", Map.of("attributeClaims", doc.attributeClaims()));
        return out;
    }

    /** Full replace of the authored doc (settings-doc discipline): roles named here override their
     *  seed entry (an empty capability list revokes); seed roles not named keep their defaults. The
     *  optional {@code identity.attributeClaims} allowlist (ABAC A1) rides the same doc — omitting it
     *  clears it, like any full-replace field. */
    private Object saveRoles(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "role settings write");
        Map<String, Roles.Def> authored = Roles.validate(body.get("roles"));
        List<String> attributeClaims = Roles.attributeClaims(body.get("identity"));
        Roles.write(root, authored, attributeClaims);
        return roles(api);
    }

    private static Map<String, Object> roleShape(String name, Roles.Def def, String source) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("capabilities", def.capabilities().stream().sorted().toList());
        if (def.dataScopes() != null) r.put("dataScopes", def.dataScopes().stream().sorted().toList());
        r.put("source", source);
        return r;
    }

    // ── access policies (ABAC A2 — authorable allow/deny over attributes; evaluation is
    //    the Enterprise policy engine's job, A3) ─────────────────────────────────────

    /** The effective policies: authored rows ({@code source: authored}) plus the engine-resident seed
     *  policies in force but never written to the doc ({@code source: seed} — e.g. the A4 space-isolation
     *  denies), so an operator sees the built-in denies too (BACKLOG §5). A seed whose name an authored
     *  policy overrides is shadowed (shown once, as authored). An unreadable doc is surfaced — the engine
     *  denies, fail-closed. Seeds appear only on the Enterprise edition (the engine supplies them). */
    private Object policies(ApiContext api) {
        AccessPolicies.Doc doc = AccessPolicies.load(api.writeRoot());
        Map<String, Object> out = new LinkedHashMap<>();
        if (doc.unreadable()) {
            out.put("policies", List.of());
            out.put("error", "access-policies.toon is unreadable — the policy engine denies (fail-closed) until it is fixed or re-saved");
            return out;
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        Set<String> authored = new LinkedHashSet<>();
        for (AccessPolicies.Policy p : doc.policies()) {
            authored.add(p.name());
            rows.add(policyShape(p, "authored"));
        }
        AccessDeciders.active().ifPresent(d -> d.seededPolicies().stream()
                .filter(p -> !authored.contains(p.name()))
                .forEach(p -> rows.add(policyShape(p, "seed"))));
        out.put("policies", rows);
        return out;
    }

    /** "Why denied?" dry-run for the current session's own subject (BACKLOG §5). Query:
     *  {@code ?route=<path>&method=<GET|PUT|…>&resourceKind=<kind>} ({@code route} required,
     *  {@code method} defaults GET, {@code resourceKind} optional for a row-level probe). Returns the
     *  engine's decision + matched policy + a per-policy trace, enforcing/auditing nothing.
     *  {@code {enabled:false}} when there is no policy engine (Personal/Standard) or no authenticated
     *  subject on the request. Testing arbitrary resource attributes is the deferred "arbitrary subject"
     *  feature — here {@code resource.space} defaults to the bound space, which the A4 seeds condition on. */
    private Object explain(HttpExchange ex) {
        Map<String, Object> out = new LinkedHashMap<>();
        java.util.Optional<AccessDecider> decider = AccessDeciders.active();
        if (decider.isEmpty()) {
            out.put("enabled", false);
            out.put("reason", "no access policy engine on this edition");
            return out;
        }
        java.util.Optional<Subject> subject = ApiContext.subject(ex);
        if (subject.isEmpty()) {
            out.put("enabled", false);
            out.put("reason", "no authenticated subject on this request");
            return out;
        }
        String route = ApiContext.query(ex, "route");
        if (route == null || route.isBlank()) throw new ApiException(422, "explain requires a 'route' to evaluate");
        String method = ApiContext.query(ex, "method");
        String action = ControlApi.actionFor((method == null ? "GET" : method).toUpperCase(Locale.ROOT), route);
        String resourceKind = ApiContext.query(ex, "resourceKind");
        AccessDecider.Explanation exp =
                decider.get().explain(ex, subject.get(), action, route, resourceKind, Map.of());
        out.put("enabled", true);
        out.put("subject", subject.get().id());
        out.put("action", action);
        out.put("route", route);
        if (resourceKind != null) out.put("resourceKind", resourceKind);
        out.put("decision", exp.decision().name());
        out.put("matchedPolicy", exp.matchedPolicy());
        out.put("trace", exp.trace().stream().map(AccessRoutes::evalShape).toList());
        return out;
    }

    private static Map<String, Object> evalShape(AccessDecider.Evaluation e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", e.name());
        r.put("effect", e.effect());
        r.put("source", e.source());
        r.put("targeted", e.targeted());
        r.put("conditionHeld", e.conditionHeld());
        return r;
    }

    /** Full replace of the authored doc (settings-doc discipline). Conditions parse-gate here — a
     *  `when` the {@code Conditions} grammar rejects is a 422, never a stored time bomb. */
    private Object savePolicies(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "access policy write");
        List<AccessPolicies.Policy> policies = AccessPolicies.validate(body.get("policies"));
        AccessPolicies.write(root, policies);
        return policies(api);
    }

    private static Map<String, Object> policyShape(AccessPolicies.Policy p, String source) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", p.name());
        r.put("effect", p.effect());
        Map<String, Object> target = new LinkedHashMap<>();
        if (!p.actions().isEmpty()) target.put("actions", p.actions().stream().sorted().toList());
        if (!p.resourceKinds().isEmpty()) target.put("resourceKinds", p.resourceKinds().stream().sorted().toList());
        if (!target.isEmpty()) r.put("target", target);
        if (!p.when().isBlank()) r.put("when", p.when());
        r.put("source", source);
        return r;
    }

    // ── catalog ───────────────────────────────────────────────────────────────────

    /** The saved catalog, or the empty one — an unsaved catalog is a state, not an error. */
    private Object catalog(ApiContext api) {
        ComponentStore store = readStore(api);
        if (store != null) {
            var saved = store.get(CATALOG_TYPE, CATALOG_ID);
            if (saved.isPresent()) return saved.get().content();
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("name", CATALOG_ID);
        empty.put("version", 0);
        empty.put("nodes", List.of());
        return empty;
    }

    private Object saveCatalog(ApiContext api, Map<String, Object> body) throws IOException {
        ComponentStore store = writeStore(api);
        Map<String, Object> doc = new LinkedHashMap<>();
        Object version = body.get("version");
        doc.put("version", version instanceof Number n ? n.intValue() : 1);
        doc.put("nodes", validNodes(body.get("nodes"), new LinkedHashSet<>()));
        return write(store, CATALOG_TYPE, CATALOG_ID, doc);
    }

    /** Validate a node forest: id (safe, unique across the tree), label, kind, action ⇒ capability. */
    private static List<Map<String, Object>> validNodes(Object nodesObj, Set<String> seen) {
        if (!(nodesObj instanceof List<?> raw))
            throw new ApiException(422, "access catalog requires a 'nodes' list");
        return raw.stream().map(n -> validNode(n, seen)).toList();
    }

    private static Map<String, Object> validNode(Object nodeObj, Set<String> seen) {
        if (!(nodeObj instanceof Map<?, ?> node))
            throw new ApiException(422, "every catalog node must be an object");
        String id = WriteGates.safeName(str(node.get("id")), "catalog node id");
        if (!seen.add(id)) throw new ApiException(422, "duplicate catalog node id '" + id + "'");
        String label = str(node.get("label"));
        if (label.isBlank()) throw new ApiException(422, "catalog node '" + id + "' requires a 'label'");
        String kind = str(node.get("kind"));
        if (!NODE_KINDS.contains(kind))
            throw new ApiException(422, "catalog node '" + id + "' has unknown kind '" + kind
                    + "' (expected one of " + NODE_KINDS + ")");
        String capability = str(node.get("capability"));
        if ("action".equals(kind) && capability.isBlank())
            throw new ApiException(422, "action node '" + id + "' requires a 'capability'");
        if (!capability.isBlank() && !Roles.KNOWN_CAPABILITIES.contains(capability))
            throw new ApiException(422, "catalog node '" + id + "' has unknown capability '" + capability
                    + "' (expected one of " + Roles.KNOWN_CAPABILITIES + ")");   // R4: manifest vocabulary
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("label", label.trim());
        out.put("kind", kind);
        if (!capability.isBlank()) out.put("capability", capability.trim());
        if (!str(node.get("icon")).isBlank()) out.put("icon", str(node.get("icon")).trim());
        if (!str(node.get("link")).isBlank()) out.put("link", str(node.get("link")).trim());
        Object children = node.get("children");
        if (children != null) out.put("children", validNodes(children, seen));
        return out;
    }

    // ── profiles ──────────────────────────────────────────────────────────────────

    private Object profiles(ApiContext api) {
        ComponentStore store = readStore(api);
        if (store == null) return List.of();
        return store.list(PROFILE_TYPE).stream()
                .map(ComponentRegistry.Component::content)
                .sorted(Comparator.comparing(c -> String.valueOf(c.get("name"))))
                .toList();
    }

    private Object saveProfile(ApiContext api, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = writeStore(api);
        String safeId = WriteGates.safeName(id, "access profile id");
        String subjectType = str(body.get("subjectType"));
        if (!SUBJECT_TYPES.contains(subjectType))
            throw new ApiException(422, "access profile requires subjectType " + SUBJECT_TYPES);
        String subjectId = str(body.get("subjectId"));
        if (subjectId.isBlank()) throw new ApiException(422, "access profile requires a 'subjectId'");
        if (!safeId.equals(subjectType + "-" + subjectId))
            throw new ApiException(422, "access profile id '" + safeId
                    + "' must equal '<subjectType>-<subjectId>' ('" + subjectType + "-" + subjectId + "')");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("subjectType", subjectType);
        doc.put("subjectId", subjectId);
        doc.put("label", str(body.get("label")).isBlank() ? subjectId : str(body.get("label")).trim());
        doc.put("grants", validGrants(body.get("grants")));
        return write(store, PROFILE_TYPE, safeId, doc);
    }

    private Object deleteProfile(ApiContext api, String id) throws IOException {
        ComponentStore store = writeStore(api);
        String safeId = WriteGates.safeName(id, "access profile id");
        if (!store.exists(PROFILE_TYPE, safeId))
            throw new ApiException(404, "access profile '" + safeId + "' not found");
        store.delete(PROFILE_TYPE, safeId);
        return Map.of("deleted", safeId);
    }

    /** Grants are a sparse map nodeId → allow|deny; absent map = no explicit grants (all inherit). */
    private static Map<String, Object> validGrants(Object grantsObj) {
        if (grantsObj == null) return Map.of();
        if (!(grantsObj instanceof Map<?, ?> grants))
            throw new ApiException(422, "access profile 'grants' must be an object of nodeId -> allow|deny");
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> g : grants.entrySet()) {
            String nodeId = str(g.getKey());
            String value = str(g.getValue());
            if (nodeId.isBlank()) throw new ApiException(422, "grant with a blank node id");
            if (!GRANT_VALUES.contains(value))
                throw new ApiException(422, "grant '" + nodeId + "' has value '" + value
                        + "' (expected one of " + GRANT_VALUES + ")");
            out.put(nodeId, value);
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Store for reads — {@code null} when writes are disabled (reads stay open, they just see nothing). */
    private static ComponentStore readStore(ApiContext api) {
        Path root = api.writeRoot();
        return root == null ? null : new ComponentStore(root.resolve("registry"));
    }

    private static ComponentStore writeStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "access config write").resolve("registry"));
    }

    private static Object write(ComponentStore store, String type, String id, Map<String, Object> content)
            throws IOException {
        try {
            return store.write(type, id, content).content();
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
