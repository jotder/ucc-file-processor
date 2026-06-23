package com.gamma.control;

import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-global, <b>un-prefixed</b> space-management CRUD — the one route group that addresses the
 * {@link SpaceManager container} itself rather than a single space's engine:
 * <pre>
 *   GET    /spaces                 list every hosted space's manifest                              [v4.7.0]
 *   POST   /spaces                 body {id, display_name?, description?} — create + boot a space  [v4.7.0]
 *   DELETE /spaces/{id}[?purge=]   deregister + drain a space; ?purge=true also deletes its files  [v4.7.0]
 * </pre>
 *
 * <p>These coexist with the per-space request seam: {@code ControlApi.dispatch} only treats a path as
 * space-scoped when it has a trailing segment ({@code /spaces/{id}/<rest>}), so the bare {@code /spaces} and
 * {@code /spaces/{id}} forms fall through to the routes here. CRUD requires the multi-space (discover) runtime —
 * a single-tenant server ({@code SpaceManager.single}) rejects create/delete with {@code 409}. This is also the
 * natural seam for the future access-control / superuser editions (an SPI consulted here), with no core
 * {@code if(edition==…)}.
 */
final class SpaceRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/spaces", (e, m) -> api.spaces().all().stream()
                .sorted(java.util.Comparator.comparing(c -> c.id().value()))
                .map(SpaceRoutes::manifest)
                .toList());

        api.post("/spaces", (e, m) -> createSpace(api, api.body(e)));

        api.delete("/spaces/([^/]+)", (e, m) -> deleteSpace(api, e, ApiContext.name(m)));
    }

    /** Create + boot a space from {@code {id, display_name?, description?}}. */
    private Object createSpace(ApiContext api, Map<String, Object> body) throws IOException {
        requireMultiSpace(api);
        String id = ApiContext.str(body, "id");
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, "body must include a valid 'id' ([a-z0-9-], 1-63 chars, not starting with '-')");
        try {
            SpaceContext ctx = api.spaces().create(SpaceId.of(id),
                    ApiContext.str(body, "display_name"), ApiContext.str(body, "description"));
            return manifest(ctx);
        } catch (IllegalStateException conflict) {   // already exists (single-mode was rejected above)
            throw new ApiException(409, conflict.getMessage());
        }
    }

    /** Deregister + drain a space; {@code ?purge=true} also deletes its directory tree. */
    private Object deleteSpace(ApiContext api, HttpExchange e, String id) throws IOException {
        requireMultiSpace(api);
        if (!SpaceId.isValid(id)) throw new ApiException(400, "invalid space id '" + id + "'");
        boolean purge = "true".equalsIgnoreCase(ApiContext.query(e, "purge"));
        if (!api.spaces().delete(SpaceId.of(id), purge))
            throw new ApiException(404, "no such space '" + id + "'");
        return Map.of("id", id, "deleted", true, "purged", purge);
    }

    private static void requireMultiSpace(ApiContext api) {
        if (!api.spaces().supportsCrud())
            throw new ApiException(409, "this server hosts a single space; launch with -Dspaces.root to manage many");
    }

    /** The id + display metadata returned for a space. */
    private static Map<String, Object> manifest(SpaceContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ctx.id().value());
        m.put("displayName", ctx.manifest().displayName());
        m.put("description", ctx.manifest().description());
        m.put("createdAt", ctx.manifest().createdAt());
        return m;
    }
}
