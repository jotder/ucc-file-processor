package com.gamma.control;

import com.gamma.event.SavedView;
import com.gamma.event.SavedViewStore;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineCodec;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * (grammar/schema/transform/sink/dataset/query/widget/dashboard) plus, since 2026-07-18,
 * {@code authored-pipeline} ({@link PipelineStore}), {@code job} ({@link JobService} — import
 * hot-registers, matching the {@code /jobs} CRUD routes), and {@code saved-view} (the event-viewer
 * {@link SavedViewStore} — a user-authored, sharable bookmarked search; <b>not</b> the derived
 * {@code pipeline.ViewStore} {@code sink.view} definitions, which are run-generated, not authored
 * config, and so are not bundle-eligible). Every supported kind is read/written through the uniform
 * {@link BundleSource} seam (below) regardless of its backing store.
 *
 * <p>{@code connection} stays out-of-scope on purpose: its profiles carry secret references, and
 * whether/how a bundle may carry a masked or reference-only credential is a policy call the plans this
 * feature is built from (`metadata-bundle.md`) never made — promote a connection via the UI mock path
 * or the whole-space zip (SPC-2) until that call is made.
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

    /** The non-{@link ComponentStore} kinds this module also serves (each has its own {@link BundleSource}). */
    private static final Set<String> OWN_STORE_KINDS = Set.of("authored-pipeline", "job", "saved-view");

    /** Supported kinds in dependency order (referenced kinds first) — the import apply order. Authored
     *  pipelines/jobs may reference the component kinds above them; saved-view has no references. */
    private static final List<String> APPLY_ORDER =
            List.of("grammar", "schema", "transform", "sink", "dataset", "query", "widget", "dashboard",
                    "reconciliation", "authored-pipeline", "job", "saved-view");

    private static boolean supported(String kind) {
        return ComponentStore.WRITABLE_TYPES.contains(kind) || OWN_STORE_KINDS.contains(kind);
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

        String sourceSpace = ApiContext.str(body, "sourceSpace");
        String exportedAt = Instant.now().toString();

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();
        for (Map<String, Object> req : requested) {
            String kind = str(req, "kind"), id = str(req, "id");
            BundleSource src = sourceFor(api, kind);
            Map<String, Object> raw = src == null ? null : src.get(id).orElse(null);
            if (raw == null) {
                missing.add(refMap(kind, id));
                continue;
            }
            Map<String, Object> content = exportContent(kind, raw);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", kind);
            item.put("id", id);
            item.put("content", content);
            if (req.get("refs") instanceof List<?>) item.put("refs", req.get("refs"));   // echo UI-derived lineage
            Map<String, Object> prov = new LinkedHashMap<>();
            prov.put("sourceSpace", sourceSpace);
            prov.put("exportedAt", exportedAt);
            prov.put("contentHash", "sha256:" + ContentHash.of(content));
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

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> item : asMapList(body.get("items"))) {
            String kind = str(item, "kind"), id = str(item, "id");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("id", id);
            BundleSource src = supported(kind) ? sourceFor(api, kind) : null;
            if (!supported(kind)) {
                row.put("status", "unsupported");
            } else {
                Map<String, Object> existing = src == null ? null : src.get(id).orElse(null);
                if (existing == null) {
                    row.put("status", "new");
                } else {
                    String targetHash = "sha256:" + ContentHash.of(existing);
                    row.put("targetHash", targetHash);
                    row.put("status", targetHash.equals(incomingHash(src, id, item)) ? "unchanged" : "drifted");
                }
            }
            items.add(row);
        }

        List<Map<String, Object>> requires = new ArrayList<>();
        for (Map<String, Object> ref : asMapList(body.get("requires"))) {
            String kind = str(ref, "kind"), id = str(ref, "id");
            Map<String, Object> row = new LinkedHashMap<>(ref);
            BundleSource src = supported(kind) ? sourceFor(api, kind) : null;
            boolean present = src != null && src.exists(id);
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
        // (Integrity checking only covers the ComponentStore kinds — authored-pipeline/job/saved-view
        // don't participate in ComponentIntegrity's ref graph.)
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
                    BundleSource src = sourceFor(api, kind);
                    if (src == null) throw new IllegalArgumentException("no store available for kind '" + kind + "'");
                    Map<String, Object> current = src.get(id).orElse(null);
                    boolean exists = current != null;
                    String action = actionFor(actions, kind, id, exists);
                    String inHash = incomingHash(src, id, item);
                    if (exists && inHash != null && inHash.equals("sha256:" + ContentHash.of(current))) {
                        status = "unchanged";   // idempotent re-promotion: identical hash ⇒ no write
                        unchanged++;
                    } else if ("skip".equals(action) || (exists && !"overwrite".equals(action))) {
                        status = "skipped";     // existing defaults to skip unless the caller opts into overwrite
                        message = "skip".equals(action) && !exists ? "explicit skip action"
                                : "already exists (pass actions {\"" + kind + "/" + id + "\":\"overwrite\"} to replace)";
                        skipped++;
                    } else {
                        src.write(id, cast(item.get("content")));
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
    private static final List<String> INTEGRITY_KINDS = List.of("dataset", "query", "widget", "dashboard", "reconciliation");

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

    // ── BundleSource: uniform read/write over one kind's own backing store ─────────

    /**
     * A get/exists/write view over one bundle kind, so export/preview/import treat every supported kind
     * alike regardless of what actually persists it (ComponentStore, PipelineStore, JobService,
     * SavedViewStore, …). {@code write} may throw {@link IllegalArgumentException} (bad id/content — a
     * per-item "failed" result) or {@link IOException} (propagated; matches {@link ComponentStore#write}).
     */
    private interface BundleSource {
        Optional<Map<String, Object>> get(String id);
        boolean exists(String id);
        Map<String, Object> write(String id, Map<String, Object> content) throws IOException;

        /**
         * Incoming {@code content} <em>as this source would store it</em> — the drift/idempotence hash must
         * be computed over the stored form, or a re-import of identical config would compare a raw map
         * against a codec-normalized one and always look drifted. Default: stamp {@code name} to {@code id}
         * (what {@link ComponentStore#write} persists); sources with a real codec round-trip through it.
         * May throw {@link RuntimeException} on malformed content (callers fall back to "no hash").
         */
        default Map<String, Object> normalized(String id, Map<String, Object> content) {
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);
            return stamped;
        }
    }

    /** Resolve the {@link BundleSource} for a supported kind, or {@code null} when its store is unavailable
     *  (no write root configured — export/preview degrade to "missing"/"unsupported", matching the prior
     *  {@code ComponentStore}-only behaviour). */
    private static BundleSource sourceFor(ApiContext api, String kind) {
        if (ComponentStore.WRITABLE_TYPES.contains(kind)) {
            Path root = componentRootOrNull(api);
            return root == null ? null : new ComponentBundleSource(new ComponentStore(root), kind);
        }
        Path root = api.writeRoot();
        if (root == null) return null;
        return switch (kind) {
            case "authored-pipeline" -> new PipelineBundleSource(new PipelineStore(root.resolve("flows")));
            case "job" -> new JobBundleSource(api);
            case "saved-view" -> new SavedViewBundleSource(api.service().savedViews());
            default -> null;
        };
    }

    /** {@link ComponentStore#WRITABLE_TYPES} kinds — the pre-existing behaviour, unwrapped from
     *  {@link ComponentRegistry.Component} to a plain content map. */
    private record ComponentBundleSource(ComponentStore store, String kind) implements BundleSource {
        public Optional<Map<String, Object>> get(String id) {
            return store.get(kind, id).map(ComponentRegistry.Component::content);
        }
        public boolean exists(String id) { return store.exists(kind, id); }
        public Map<String, Object> write(String id, Map<String, Object> content) throws IOException {
            return store.write(kind, id, content).content();
        }
    }

    /** {@code authored-pipeline} — {@link PipelineStore}, round-tripped through {@link PipelineCodec}. */
    private record PipelineBundleSource(PipelineStore store) implements BundleSource {
        public Optional<Map<String, Object>> get(String id) {
            return store.get(id).map(PipelineCodec::toMap);
        }
        public boolean exists(String id) { return store.exists(id); }
        public Map<String, Object> write(String id, Map<String, Object> content) throws IOException {
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);   // in-file identity == URL id, mirroring ComponentStore.write
            PipelineGraph g;
            try {
                g = PipelineCodec.fromMap(stamped);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(ex.getMessage());
            }
            return PipelineCodec.toMap(store.write(id, g));
        }
        public Map<String, Object> normalized(String id, Map<String, Object> content) {
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);
            return PipelineCodec.toMap(PipelineCodec.fromMap(stamped));   // the codec's stored form
        }
    }

    /**
     * {@code job} — {@link JobService}'s live registry (read; the same source of truth {@code /jobs}
     * CRUD uses) + the {@code <write-root>/jobs/<name>_job.toon} file (write, jailed like
     * {@code JobRoutes.jobFile}) — an import hot-registers via {@link JobService#upsertJob}, exactly
     * like the {@code /jobs} write routes, so an imported job takes effect without a restart.
     */
    private record JobBundleSource(ApiContext api) implements BundleSource {
        public Optional<Map<String, Object>> get(String id) {
            // A plain read must not instantiate a JobService that doesn't exist yet (matches GET /jobs'
            // use of the non-creating jobService() accessor, unlike the write path below).
            return api.service().jobService().flatMap(s -> s.jobConfig(id)).map(JobConfig::toMap);
        }
        public boolean exists(String id) { return get(id).isPresent(); }
        public Map<String, Object> write(String id, Map<String, Object> content) throws IOException {
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);
            JobConfig c;
            try {
                c = JobConfig.fromMap(Map.of("job", stamped));
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(ex.getMessage());
            }
            String safe = WriteGates.safeName(c.name(), "job name");
            Path target = WriteGates.jail(api.writeRoot(),
                    api.writeRoot().resolve("jobs").resolve(safe + "_job.toon"), "resolved path");
            byte[] bytes = com.gamma.config.io.ConfigCodec.toToon(Map.of("job", c.toMap()))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.gamma.util.AtomicFiles.write(target, bytes, ".job-");
            api.service().jobServiceOrCreate().upsertJob(c);
            return c.toMap();
        }
        public Map<String, Object> normalized(String id, Map<String, Object> content) {
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);
            return JobConfig.fromMap(Map.of("job", stamped)).toMap();   // the registry's stored form
        }
    }

    /**
     * {@code saved-view} — the event-viewer {@link SavedViewStore} (a user-authored bookmarked search;
     * <b>not</b> the run-generated {@code pipeline.ViewStore} {@code sink.view} definitions, which aren't
     * authored config and so aren't bundle-eligible).
     */
    private record SavedViewBundleSource(SavedViewStore store) implements BundleSource {
        public Optional<Map<String, Object>> get(String id) {
            return Optional.ofNullable(store.get(id)).map(SavedView::toMap);
        }
        public boolean exists(String id) { return store.get(id) != null; }
        @SuppressWarnings("unchecked")
        public Map<String, Object> write(String id, Map<String, Object> content) {
            Map<String, String> filters = content.get("filters") instanceof Map<?, ?> f
                    ? f.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue()),
                            (a, b) -> b, LinkedHashMap::new))
                    : Map.of();
            long createdAt = content.get("createdAt") instanceof Number n ? n.longValue()
                    : System.currentTimeMillis();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("saved-view id is required");
            return store.save(new SavedView(id, filters, createdAt)).toMap();
        }
        public Map<String, Object> normalized(String id, Map<String, Object> content) {
            long createdAt = content.get("createdAt") instanceof Number n ? n.longValue() : 0L;
            @SuppressWarnings("unchecked")
            Map<String, String> filters = content.get("filters") instanceof Map<?, ?> f
                    ? f.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                            e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue()),
                            (a, b) -> b, LinkedHashMap::new))
                    : Map.of();
            return new SavedView(id, filters, createdAt).toMap();   // createdAt-less imports hash as 0 ⇒ drift, honestly
        }
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
                    + APPLY_ORDER + "; connection is not exportable server-side (secret-policy gated)");
    }

    /**
     * The incoming item's content hash <em>as it would be stored</em> — recomputed server-side via the
     * source's {@link BundleSource#normalized} (each store's own persisted form), so the hash is comparable
     * to the target's stored content. Not trusted from the carried {@code provenance.contentHash} (that is
     * audit metadata), so drift/idempotence reflect the actual content and v1 items (no provenance) work
     * too. {@code null} when the item has no content object or its content doesn't parse — the caller then
     * skips the unchanged-check and lets the write path report the real error.
     */
    private static String incomingHash(BundleSource src, String id, Map<String, Object> item) {
        if (!(item.get("content") instanceof Map<?, ?>)) return null;
        try {
            return "sha256:" + ContentHash.of(src.normalized(id, cast(item.get("content"))));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String actionFor(Map<String, Object> actions, String kind, String id, boolean exists) {
        Object a = actions.get(kind + "/" + id);
        if (a != null) return a.toString();
        return exists ? "skip" : "import";
    }

    /**
     * A bundle carries <b>configuration</b>, not operational history: a reconciliation's run state
     * ({@code breaks}, {@code lastRunAt}) is stripped at export — the target starts a fresh Break
     * lifecycle. Every other kind exports verbatim.
     */
    private static Map<String, Object> exportContent(String kind, Map<String, Object> content) {
        if (!"reconciliation".equals(kind)) return content;
        Map<String, Object> sanitized = new LinkedHashMap<>(content);
        sanitized.remove("breaks");
        sanitized.remove("lastRunAt");
        return sanitized;
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
