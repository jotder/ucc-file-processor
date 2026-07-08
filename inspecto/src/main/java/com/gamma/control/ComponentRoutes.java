package com.gamma.control;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineReferences;
import com.gamma.pipeline.exec.ComponentPreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component registry CRUD + scratch preview/test ({@code /components*}, T19/T18, §7.1):
 * grammar/schema/transform/sink components under {@code <write-root>/registry}. Extracted verbatim
 * from {@link ControlApi}: identical routes, HTTP statuses and safe-delete semantics. Safe-delete
 * checks flow references via the shared {@link PipelineRoutes#liftedFlows} projection; previews run on a
 * throwaway DuckDB and never touch production output.
 */
final class ComponentRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ComponentRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/components/([^/]+)", (e, m) -> componentList(api, ApiContext.name(m)));
        api.get("/components/([^/]+)/([^/]+)", (e, m) -> componentById(api, e, ApiContext.name(m), ApiContext.param(m, 2)));
        // Writes require canAuthorWorkbench (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/components/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createComponent(api, e, ApiContext.name(m), api.body(e))));
        api.put("/components/([^/]+)/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> updateComponent(api, e, ApiContext.name(m), ApiContext.param(m, 2), api.body(e))));
        api.delete("/components/([^/]+)/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteComponent(api, ApiContext.name(m), ApiContext.param(m, 2))));
        // T18 dry-run/test: preview a component over a sample through the production logic (scratch-only).
        api.post("/components/transform/([^/]+)/test", (e, m) -> previewTransform(api, ApiContext.name(m), api.body(e)));
        api.post("/components/grammar/([^/]+)/test", (e, m) -> previewGrammar(api, ApiContext.name(m), api.body(e)));
        api.post("/components/schema/([^/]+)/test", (e, m) -> previewSchema(api, ApiContext.name(m), api.body(e)));
        api.post("/components/sink/([^/]+)/test", (e, m) -> previewSink(api, ApiContext.name(m), api.body(e)));
    }

    /** The registry root under the write root, or {@code null} when writes are disabled (no write root). */
    private Path componentRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
    }

    private ComponentStore componentStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "component write").resolve("registry"));
    }

    /** The JSON shape for one component: identity + version metadata (W3: contentHash/created/modified) + content. */
    private static Map<String, Object> componentDoc(ComponentRegistry.Component c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", c.type());
        m.put("name", c.name());
        m.put("ref", c.ref());
        m.put("contentHash", ContentHash.of(c.content()));   // = the ETag / optimistic-lock token
        addFileTimes(m, c.path());                           // created/modified from filesystem attrs
        m.put("content", c.content());
        return m;
    }

    /** Add ISO-8601 {@code created}/{@code modified} from the file's attributes ({@code null} if unavailable). */
    private static void addFileTimes(Map<String, Object> m, Path p) {
        String created = null, modified = null;
        try {
            var attrs = java.nio.file.Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
            created = attrs.creationTime().toInstant().toString();
            modified = attrs.lastModifiedTime().toInstant().toString();
        } catch (IOException | UnsupportedOperationException ignored) {
            // filesystem without creation time, or a transient read error — timestamps are best-effort
        }
        m.put("created", created);
        m.put("modified", modified);
    }

    /** {@code GET /components/{type}} — list components of a type (empty when no registry/write root). */
    private Object componentList(ApiContext api, String type) {
        Path root = componentRootOrNull(api);
        if (root == null) return List.of();
        try {
            return new ComponentStore(root).list(type).stream().map(ComponentRoutes::componentDoc).toList();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /**
     * {@code GET /components/{type}/{id}} — one component; 404 if absent. Carries a strong {@code ETag}
     * (= the content hash); a matching {@code If-None-Match} yields {@code 304} (W3 caching).
     */
    private Object componentById(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id) throws IOException {
        Path root = componentRootOrNull(api);
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        // SEC-7(b): the only verbs on a registry component are the Workbench-authoring family.
        ApiContext.resourcePermissions(ex, java.util.Set.of("canAuthorWorkbench"));
        String etag = ETags.of(ContentHash.of(c.content()));
        if (ETags.isFresh(ex, etag)) return ETags.notModified(ex, etag);
        ETags.set(ex, etag);
        return componentDoc(c);
    }

    /** {@code POST /components/{type}} — create a component (id from body {@code id}/{@code name}); 409 if it exists. */
    private Object createComponent(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, Map<String, Object> body) throws IOException {
        ComponentStore store = componentStore(api);
        String id = ApiContext.str(body, "id");
        if (id == null || id.isBlank()) id = ApiContext.str(body, "name");
        if (id == null || id.isBlank()) throw new ApiException(400, "body must include 'id' (or 'name')");
        if (componentExists(store, type, id))
            throw new ApiException(409, type + " component '" + id + "' already exists (use PUT to update)");
        return writeComponent(store, ex, type, id, body);
    }

    /**
     * {@code PUT /components/{type}/{id}} — replace a component; 404 if absent. Honours an optional
     * {@code If-Match} precondition against the current content hash → {@code 409 CONFLICT_STALE_VERSION}
     * on a stale write (W3 optimistic locking).
     */
    private Object updateComponent(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = componentStore(api);
        ComponentRegistry.Component current = existing(store, type, id);
        if (current == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        ETags.requireMatch(ex, ETags.of(ContentHash.of(current.content())));
        return writeComponent(store, ex, type, id, body);
    }

    /** The current component or {@code null}; maps a bad type to the standard 400. */
    private static ComponentRegistry.Component existing(ComponentStore store, String type, String id) {
        try {
            return store.get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** {@code DELETE /components/{type}/{id}} — safe-delete; 404 if absent, 409 if a flow references it. */
    private Object deleteComponent(ApiContext api, String type, String id) throws IOException {
        ComponentStore store = componentStore(api);
        if (!componentExists(store, type, id)) throw new ApiException(404, "no " + type + " component '" + id + "'");
        List<String> refs = PipelineReferences.referencedBy(type + "/" + id, PipelineRoutes.liftedFlows(api.service()));
        if (!refs.isEmpty())
            throw new ApiException(409, type + " component '" + id + "' is referenced by flow(s): "
                    + String.join(", ", refs));
        // Deletion fence extends to the Exchange: an offered item still shared with other Spaces cannot be
        // deleted out from under its consumers (fail-closed; revoke the grant(s) first).
        List<String> consumers = activeConsumers(api, type, id);
        if (!consumers.isEmpty())
            throw new ApiException(409, type + " component '" + id + "' is shared with space(s): "
                    + String.join(", ", consumers) + " — revoke the grant(s) first");
        boolean removed;
        try {
            removed = store.delete(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("type", type, "id", id, "deleted", true, "fileRemoved", removed);
    }

    /**
     * {@code POST /components/transform/{id}/test} — dry-run a transform component over {@code sampleRows}
     * through the production {@link com.gamma.pipeline.exec.RowShaper} on a throwaway DuckDB (T18, §7.2). 404 if
     * the component is absent, 422 if it is not a {@code transform.*} type, 400 on a bad sample / unsupported
     * operator. Never touches production output.
     */
    @SuppressWarnings("unchecked")
    private Object previewTransform(ApiContext api, String id, Map<String, Object> body) {
        Path root = componentRootOrNull(api);
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get("transform", id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no transform component '" + id + "'");
        String type = ApiContext.str(c.content(), "type");
        if (type == null || !type.startsWith("transform."))
            throw new ApiException(422, "component '" + id + "' is not a transform ('type: transform.*' required)");

        PipelineNode node = new PipelineNode(id, type, c.content(), null);
        try {
            return ComponentPreview.transform(node, ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /components/grammar/{id}/test} — parse raw {@code sampleText} with a grammar component's CSV
     * dialect through the production {@code read_csv} on a throwaway DuckDB (T18, §7.2). 404 if absent, 400 on
     * empty input, 422 on a parse error. Never touches production output.
     */
    private Object previewGrammar(ApiContext api, String id, Map<String, Object> body) {
        ComponentRegistry.Component c = requireComponent(api, "grammar", id);
        try {
            return ComponentPreview.grammar(c.content(), sampleText(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /components/schema/{id}/test} — {@code TRY_CAST} {@code sampleRows} against a schema
     * component's typed fields, splitting {@code data} / {@code rejected}, on a throwaway DuckDB (T18, §7.2).
     * 404 if absent, 400 on a bad sample, 422 on a cast/SQL error. Never touches production output.
     */
    private Object previewSchema(ApiContext api, String id, Map<String, Object> body) {
        ComponentRegistry.Component c = requireComponent(api, "schema", id);
        try {
            return ComponentPreview.schema(c.content(), ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (java.sql.SQLException | IOException e) {
            throw new ApiException(422, "preview failed: " + e.getMessage());
        }
    }

    /**
     * {@code POST /components/sink/{id}/test} — scratch-validate a sink component against {@code sampleRows}
     * (store/format/partition checks; row count + bounded sample, no write) (T18, §7.2). 404 if absent, 400 on
     * a bad sample.
     */
    private Object previewSink(ApiContext api, String id, Map<String, Object> body) {
        ComponentRegistry.Component c = requireComponent(api, "sink", id);
        try {
            return ComponentPreview.sink(c.content(), ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Load a component by {@code type}/{@code id} or fail with the standard 400/404 (shared by the preview handlers). */
    private ComponentRegistry.Component requireComponent(ApiContext api, String type, String id) {
        Path root = componentRootOrNull(api);
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        return c;
    }

    /** Extract raw {@code sampleText} from a request body (the text a grammar would parse); empty if absent. */
    private static String sampleText(Map<String, Object> body) {
        Object t = body.get("sampleText");
        return t == null ? "" : t.toString();
    }

    /** Consumer Spaces holding an <em>active</em> Exchange grant on {@code type/id} owned by the bound Space. */
    private static List<String> activeConsumers(ApiContext api, String type, String id) {
        com.gamma.exchange.Exchange ex = com.gamma.exchange.Exchange.under(api.spaces().containerRoot());
        if (!ex.enabled()) return List.of();
        String owner = com.gamma.event.EventLog.currentSpaceId();
        return ex.grants().stream()
                .filter(g -> com.gamma.exchange.ShareGrant.ACTIVE.equals(g.status())
                        && type.equals(g.kind()) && id.equals(g.item()) && owner.equals(g.owner()))
                .map(com.gamma.exchange.ShareGrant::consumer)
                .toList();
    }

    private static boolean componentExists(ComponentStore store, String type, String id) {
        try {
            return store.exists(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Write a component: the body is the content (the routing-only {@code id} key is stripped); 422 on bad input.
     *  The written resource's new {@code ETag} rides the response so a client can chain a conditional update. */
    private Object writeComponent(ComponentStore store, com.sun.net.httpserver.HttpExchange ex, String type, String id, Map<String, Object> body) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>(body);
        content.remove("id");   // routing key, not content (the store stamps name=id)
        try {
            ComponentRegistry.Component c = store.write(type, id, content);
            log.info("[COMPONENT-WRITE] wrote {}", c.ref());
            ETags.set(ex, ETags.of(ContentHash.of(c.content())));
            return componentDoc(c);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }
}
