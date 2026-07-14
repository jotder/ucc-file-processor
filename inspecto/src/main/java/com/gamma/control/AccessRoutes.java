package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lens access configuration routes ({@code /access/*} — {@code docs/superpower/lens-access-config-design.md},
 * vocabulary {@code docs/GLOSSARY.md} §1-A): the <b>Access Catalog</b> (the tree of menus → panes →
 * capability-bound action nodes, derived and saved by the UI) and one <b>Access Profile</b> per subject
 * (today {@code subjectType: lens} — Builder/Ops/Business visibility shaping; under RBAC the same
 * documents carry {@code subjectType: role} and the security module enforces them server-side).
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
        api.get("/access/catalog", (e, m) -> catalog(api));
        api.put("/access/catalog", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> saveCatalog(api, api.body(e))));
        api.get("/access/profiles", (e, m) -> profiles(api));
        api.put("/access/profiles/([^/]+)", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> saveProfile(api, ApiContext.name(m), api.body(e))));
        api.delete("/access/profiles/([^/]+)", ApiContext.withCapability("canConfigureAccess",
                (e, m) -> deleteProfile(api, ApiContext.name(m))));
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
