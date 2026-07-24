package com.gamma.control;

import com.gamma.event.EventLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-space Menu tree (Menu Builder) — the real backend for the contract the UI froze mock-first
 * (menu-builder plan §4.7):
 * <pre>
 *   GET /nav/menus   the space's Menu tree {space, version, nodes} (empty tree before any save)
 *   PUT /nav/menus   replace the space's Menu tree (write-root gated, capability-gated)
 * </pre>
 *
 * <p>Space-scoped through the standard {@code /spaces/{id}/…} request seam, stored as
 * {@code nav-menus.toon} in the bound space's config tree ({@link ApiContext#writeRoot()}) — the same
 * discipline as {@link SettingsRoutes}. The filename is fixed (no caller-supplied path ⇒ no jail gate);
 * a PUT replaces the whole document (a singleton ⇒ no conflict gate).
 */
final class NavRoutes implements RouteModule {

    private static final String MENUS_FILE = "nav-menus.toon";

    @Override
    public void register(ApiContext api) {
        api.get("/nav/menus", (e, m) -> ETags.respond(e, readMenus(api)));
        api.put("/nav/menus", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeMenus(api, api.body(e))));
    }

    private Object readMenus(ApiContext api) {
        Path root = api.writeRoot();
        NavMenus menus = root == null ? NavMenus.EMPTY : NavMenus.read(root.resolve(MENUS_FILE));
        return shape(menus);
    }

    private Object writeMenus(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "menu write");
        if (!(body.get("version") instanceof Number v) || v.intValue() != NavMenus.VERSION)
            throw new ApiException(422, "version must be " + NavMenus.VERSION);
        NavMenus menus;
        try {
            menus = new NavMenus(NavMenus.sanitize(body.get("nodes")));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(422, ex.getMessage());
        }
        menus.write(root.resolve(MENUS_FILE));
        return shape(menus);
    }

    /** The wire shape the UI's {@code MenuService} expects — {@code space} stamped from the request seam. */
    private static Map<String, Object> shape(NavMenus menus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("space", EventLog.currentSpaceId());
        m.put("version", NavMenus.VERSION);
        m.put("nodes", menus.nodes());
        return m;
    }
}
