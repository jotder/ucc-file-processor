package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Metadata Bundle v2 — selective, config-only transfer of Component definitions between instances
 * (SPC-4, {@code docs/superpower/metadata-bundle.md} + {@code metadata-network-design.md} §4).
 * The bundle envelope is deliberately <em>self-describing</em> — the UI derives the closure, lineage
 * {@code refs} and aggregated {@code requires}; the backend's job is the authoritative half the mock
 * cannot provide: real component {@code content}, a real {@code contentHash} (drift detection +
 * idempotent re-promotion), a real fit-check against <em>this</em> instance's graph, and real
 * persistence on import.
 *
 * <p><b>Scope.</b> Covers the {@link ComponentStore#WRITABLE_TYPES} kinds
 * (grammar/schema/transform/sink/dataset/query/widget/dashboard) — the ones that persist for real
 * since the W3 store widening. {@code connection} (secret-aware CRUD), {@code authored-pipeline},
 * {@code job}, and the saved-view kinds live in their own stores and are reported as
 * {@code unsupported} here (promote them via the UI mock path or the whole-space zip, SPC-2) until a
 * later slice teaches this module their stores.
 *
 * <p>Routes: {@code POST /bundle/export} (read; content+hash for a requested item list),
 * {@code POST /bundle/preview} (read; New/Exists/drift fit-check + {@code requires} classification,
 * no writes), {@code POST /bundle/import} (write; sequential upsert in dependency order, reporting
 * per-item without aborting the rest). Import is gated on {@code canAuthorWorkbench} (a no-op on
 * Personal) then the write-root 503 gate — matching {@link ComponentRoutes}.
 */
final class BundleRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(BundleRoutes.class);

    static final String FORMAT = "inspecto-metadata-bundle";

    /** Supported kinds in dependency order (referenced kinds first) — the import apply order. */
    private static final List<String> APPLY_ORDER =
            List.of("grammar", "schema", "transform", "sink", "dataset", "query", "widget", "dashboard");

    private static boolean supported(String kind) {
        return ComponentStore.WRITABLE_TYPES.contains(kind);
    }

    @Override
    public void register(ApiContext api) {
        api.post("/bundle/export", (e, m) -> exportBundle(api, api.body(e)));
        api.post("/bundle/preview", (e, m) -> previewBundle(api, api.body(e)));
        // Import writes real components → Builder capability (W6; no-op on Personal) then the write-root gate.
        api.post("/bundle/import", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> importBundle(api, api.body(e))));
    }

    // ── export ───────────────────────────────────────────────────────────────────

    /**
     * {@code POST /bundle/export} — body {@code {items:[{kind,id,refs?}], provenance?, sourceSpace?, requires?}}.
     * Reads each requested component from this instance and returns {@code {bundle:<v2 envelope>, missing:[…]}}:
     * the envelope carries real {@code content} + per-item {@code provenance.contentHash}; requested items that
     * do not exist here are omitted and reported under {@code missing} (a partial bundle is still valid — the
     * caller decides). An unsupported kind is a 422 (the backend's honest boundary), not a silent omission.
     */
    private Object exportBundle(ApiContext api, Map<String, Object> body) {
        List<Map<String, Object>> requested = asMapList(body.get("items"));
        if (requested.isEmpty()) throw new ApiException(422, "export body must include a non-empty 'items' array");
        rejectUnsupported(requested);

        Path root = componentRootOrNull(api);
        String sourceSpace = ApiContext.str(body, "sourceSpace");
        String exportedAt = Instant.now().toString();

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        ComponentStore store = root == null ? null : new ComponentStore(root);
        for (Map<String, Object> req : requested) {
            String kind = str(req, "kind"), id = str(req, "id");
            ComponentRegistry.Component c = store == null ? null : store.get(kind, id).orElse(null);
            if (c == null) {
                missing.add(refMap(kind, id));
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", kind);
            item.put("id", id);
            item.put("content", c.content());
            if (req.get("refs") instanceof List<?>) item.put("refs", req.get("refs"));   // echo UI-derived lineage
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("sourceSpace", sourceSpace);
            prov.put("exportedAt", exportedAt);
            prov.put("contentHash", "sha256:" + ContentHash.of(c.content()));
            item.put("provenance", prov);
            items.add(item);
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", FORMAT);
        bundle.put("version", 2);
        bundle.put("exportedAt", exportedAt);
        bundle.put("sourceSpace", sourceSpace);
        if (body.get("provenance") instanceof Map<?, ?> p) bundle.put("provenance", p);
        if (body.get("requires") instanceof List<?> r) bundle.put("requires", r);
        bundle.put("items", items);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bundle", bundle);
        out.put("missing", missing);
        return out;
    }

    // ── preview (fit-check) ────────────────────────────────────────────────────────

    /**
     * {@code POST /bundle/preview} — body = a bundle envelope. Read-only fit-check against this instance:
     * per item {@code status} = {@code new} | {@code unchanged} | {@code drifted} | {@code unsupported}
     * (unchanged/drifted compare the item's {@code provenance.contentHash} to the target's current hash),
     * and each top-level {@code requires} entry classified {@code satisfied} | {@code missing}. No writes.
     */
    private Object previewBundle(ApiContext api, Map<String, Object> body) {
        validateEnvelope(body);
        Path root = componentRootOrNull(api);
        ComponentStore store = root == null ? null : new ComponentStore(root);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : asMapList(body.get("items"))) {
            String kind = str(item, "kind"), id = str(item, "id");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("id", id);
            if (!supported(kind)) {
                row.put("status", "unsupported");
            } else {
                ComponentRegistry.Component existing = store == null ? null : store.get(kind, id).orElse(null);
                if (existing == null) {
                    row.put("status", "new");
                } else {
                    String targetHash = "sha256:" + ContentHash.of(existing.content());
                    row.put("targetHash", targetHash);
                    row.put("status", targetHash.equals(incomingHash(id, item)) ? "unchanged" : "drifted");
                }
            }
            items.add(row);
        }

        List<Map<String, Object>> requires = new ArrayList<>();
        for (Map<String, Object> ref : asMapList(body.get("requires"))) {
            String kind = str(ref, "kind"), id = str(ref, "id");
            Map<String, Object> row = new LinkedHashMap<>(ref);
            boolean present = supported(kind) && store != null && store.exists(kind, id);
            row.put("status", present ? "satisfied" : "missing");
            requires.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("requires", requires);
        return out;
    }

    // ── import (apply) ───────────────────────────────────────────────────────────

    /**
     * {@code POST /bundle/import} — body {@code {bundle:<envelope>, actions?:{"<kind>/<id>":"import|overwrite|skip"}}}.
     * Applies items in dependency order (referenced kinds first). Default action: a new item imports, an existing
     * one is skipped; an incoming item whose hash equals the target's is {@code unchanged} (idempotent
     * re-promotion). Reports per item ({@code imported}/{@code overwritten}/{@code skipped}/{@code unchanged}/
     * {@code failed}) without aborting the batch.
     */
    private Object importBundle(ApiContext api, Map<String, Object> body) throws IOException {
        // Gate 1 — writes disabled → 503.
        Path registry = WriteGates.requireWriteRoot(api, "bundle import").resolve("registry");
        // Gate 2 — structural validation → 422.
        Map<String, Object> bundle = body.get("bundle") instanceof Map<?, ?> ? cast(body.get("bundle")) : body;
        validateEnvelope(bundle);
        Map<String, Object> actions = body.get("actions") instanceof Map<?, ?> ? cast(body.get("actions")) : Map.of();

        ComponentStore store = new ComponentStore(registry);
        List<Map<String, Object>> ordered = new ArrayList<>(asMapList(bundle.get("items")));
        ordered.sort((a, b) -> Integer.compare(orderOf(str(a, "kind")), orderOf(str(b, "kind"))));

        // Gate 3 — referential integrity (System Maintenance MNT-16) → 422, fail-closed, before any
        // write: an import may not INTRODUCE broken references. Findings are computed over
        // (registry ∪ incoming) minus the registry's pre-existing findings, so a bundle whose items
        // resolve each other passes and an old broken ref already on disk never blocks a new import.
        List<String> introduced = introducedIntegrityFindings(store, ordered);
        if (!introduced.isEmpty())
            throw new ApiException(422, "bundle fails referential integrity — import would introduce: " + introduced);

        List<Map<String, Object>> results = new ArrayList<>();
        int imported = 0, overwritten = 0, skipped = 0, unchanged = 0, failed = 0;
        for (Map<String, Object> item : ordered) {
            String kind = str(item, "kind"), id = str(item, "id");
            String status;
            String message = null;
            if (!supported(kind)) {
                status = "skipped";
                message = "unsupported kind (promote via the UI or whole-space export)";
                skipped++;
            } else if (!(item.get("content") instanceof Map<?, ?>)) {
                status = "failed";
                message = "item has no 'content' object";
                failed++;
            } else {
                try {
                    ComponentRegistry.Component current = store.get(kind, id).orElse(null);
                    boolean exists = current != null;
                    String action = actionFor(actions, kind, id, exists);
                    String inHash = incomingHash(id, item);
                    if (exists && inHash != null
                            && inHash.equals("sha256:" + ContentHash.of(current.content()))) {
                        status = "unchanged";   // idempotent re-promotion: identical hash ⇒ no write
                        unchanged++;
                    } else if ("skip".equals(action) || (exists && !"overwrite".equals(action))) {
                        status = "skipped";     // existing defaults to skip unless the caller opts into overwrite
                        skipped++;
                    } else {
                        store.write(kind, id, cast(item.get("content")));
                        status = exists ? "overwritten" : "imported";
                        if (exists) overwritten++; else imported++;
                    }
                } catch (IllegalArgumentException ex) {
                    status = "failed";          // bad id / unwritable content — reported, batch continues
                    message = ex.getMessage();
                    failed++;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("id", id);
            row.put("status", status);
            if (message != null) row.put("message", message);
            results.add(row);
        }
        log.info("[BUNDLE-IMPORT] {} imported, {} overwritten, {} skipped, {} unchanged, {} failed",
                imported, overwritten, skipped, unchanged, failed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", imported);
        out.put("overwritten", overwritten);
        out.put("skipped", skipped);
        out.put("unchanged", unchanged);
        out.put("failed", failed);
        out.put("results", results);
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** The integrity kinds the shared rules cover (see {@link com.gamma.pipeline.ComponentIntegrity}). */
    private static final List<String> INTEGRITY_KINDS = List.of("dataset", "query", "widget", "dashboard");

    /** Broken-reference findings the incoming items would introduce: findings over
     *  (registry ∪ incoming) minus the findings the registry already has on its own. */
    private static List<String> introducedIntegrityFindings(ComponentStore store, List<Map<String, Object>> items) {
        Map<String, List<ComponentRegistry.Component>> existing = new LinkedHashMap<>();
        Map<String, List<ComponentRegistry.Component>> union = new LinkedHashMap<>();
        for (String kind : INTEGRITY_KINDS) {
            List<ComponentRegistry.Component> current = store.list(kind);
            existing.put(kind, current);
            union.put(kind, new ArrayList<>(current));
        }
        for (Map<String, Object> item : items) {
            String kind = str(item, "kind"), id = str(item, "id");
            if (kind == null || id == null || !INTEGRITY_KINDS.contains(kind)) continue;
            if (!(item.get("content") instanceof Map<?, ?>)) continue;
            List<ComponentRegistry.Component> list = union.get(kind);
            list.removeIf(c -> c.name().equals(id));   // an overwrite replaces the stored copy
            list.add(new ComponentRegistry.Component(kind, id, null, cast(item.get("content"))));
        }
        List<String> introduced = new ArrayList<>(com.gamma.pipeline.ComponentIntegrity.brokenRefs(union));
        introduced.removeAll(com.gamma.pipeline.ComponentIntegrity.brokenRefs(existing));
        return introduced;
    }

    private static Path componentRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
    }

    /** Structural envelope validation (§4 step 1): format + version + a non-empty items array → 422 otherwise. */
    private static void validateEnvelope(Map<String, Object> env) {
        if (!FORMAT.equals(str(env, "format")))
            throw new ApiException(422, "not an Inspecto metadata bundle (format must be '" + FORMAT + "')");
        Object v = env.get("version");
        int version = v instanceof Number n ? n.intValue() : -1;
        if (version != 1 && version != 2)
            throw new ApiException(422, "unsupported bundle version (expected 1 or 2)");
        if (asMapList(env.get("items")).isEmpty())
            throw new ApiException(422, "bundle has no items");
    }

    /** Reject a request naming any kind outside the backend's supported set → 422 (the honest boundary). */
    private static void rejectUnsupported(List<Map<String, Object>> items) {
        List<String> bad = items.stream().map(i -> str(i, "kind"))
                .filter(k -> k != null && !supported(k)).distinct().toList();
        if (!bad.isEmpty())
            throw new ApiException(422, "unsupported kind(s) " + bad + " — backend bundle covers "
                    + APPLY_ORDER + "; connection/pipeline/job/views are not yet exportable server-side");
    }

    /**
     * The incoming item's content hash <em>as it would be stored</em> — recomputed server-side from its
     * {@code content} with {@code name} stamped to {@code id} (mirroring {@link ComponentStore#write}), so the
     * hash is comparable to the target's stored content. Not trusted from the carried
     * {@code provenance.contentHash} (that is audit metadata), so drift/idempotence reflect the actual content
     * and v1 items (no provenance) work too. {@code null} when the item has no content object.
     */
    private static String incomingHash(String id, Map<String, Object> item) {
        if (!(item.get("content") instanceof Map<?, ?>)) return null;
        Map<String, Object> normalized = new LinkedHashMap<>(cast(item.get("content")));
        normalized.put("name", id);   // == what ComponentStore.write persists, so the hash matches the stored form
        return "sha256:" + ContentHash.of(normalized);
    }

    private static String actionFor(Map<String, Object> actions, String kind, String id, boolean exists) {
        Object a = actions.get(kind + "/" + id);
        if (a != null) return a.toString();
        return exists ? "skip" : "import";
    }

    private static int orderOf(String kind) {
        int i = APPLY_ORDER.indexOf(kind);
        return i < 0 ? APPLY_ORDER.size() : i;   // unsupported kinds sort last
    }

    private static Map<String, Object> refMap(String kind, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", id);
        return m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list)
            for (Object e : list) if (e instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        return out;
    }
}
