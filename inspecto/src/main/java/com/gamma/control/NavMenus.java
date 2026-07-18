package com.gamma.control;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-space Menu tree (Menu Builder) — user-curated sidebar navigation, persisted as
 * {@code nav-menus.toon} in the space's config tree (crash-safe TOON, mirroring {@link BrandingSettings}).
 * The wire contract is the shape the UI froze mock-first (menu-builder plan §4.7):
 * {@code {space, version: 1, nodes: [{id, title, icon?, children?|binding?{kind, componentId}}]}}.
 *
 * <p>On disk only {@code {version, nodes}} is stored — the file already lives inside one space's config
 * tree, so persisting the space id would be redundant and could go stale; the route stamps {@code space}
 * from the request seam on every response.
 *
 * <p>{@link #sanitize} is both the 422 validation walk and the canonicalizer: it whitelist-copies the
 * known node fields (junk keys never reach disk), rejects blank {@code id}/{@code title}, duplicate ids,
 * a node carrying both {@code children} and {@code binding}, and a runaway node count.
 */
record NavMenus(List<Map<String, Object>> nodes) {

    static final NavMenus EMPTY = new NavMenus(List.of());
    static final int VERSION = 1;
    /** Defence-in-depth cap on total nodes across the whole tree (the UI authors dozens, not thousands). */
    static final int MAX_NODES = 2000;

    /** Write to {@code nav-menus.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", VERSION);
        m.put("nodes", nodes);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".nav-menus-");
    }

    /** Read {@code nav-menus.toon} at {@code path}; missing/unreadable/invalid → {@link #EMPTY}. */
    static NavMenus read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            return new NavMenus(sanitize(m.get("nodes")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /** Validate + canonicalize a raw {@code nodes} value. Throws {@link IllegalArgumentException} on a
     *  structural violation (the route maps it to 422). {@code null} → an empty tree. */
    static List<Map<String, Object>> sanitize(Object raw) {
        return sanitizeNodes(raw, new HashSet<>(), new int[1]);
    }

    private static List<Map<String, Object>> sanitizeNodes(Object raw, Set<String> ids, int[] count) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("nodes must be a list");
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> node)) throw new IllegalArgumentException("each node must be an object");
            if (++count[0] > MAX_NODES) throw new IllegalArgumentException("too many nodes (max " + MAX_NODES + ")");
            String id = requireText(node, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("duplicate node id '" + id + "'");
            String title = requireText(node, "title");
            Object children = node.get("children");
            Object binding = node.get("binding");
            if (children != null && binding != null)
                throw new IllegalArgumentException("node '" + id + "': children and binding are mutually exclusive");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("title", title);
            if (node.get("icon") instanceof String icon && !icon.isBlank()) c.put("icon", icon);
            if (binding != null) {
                if (!(binding instanceof Map<?, ?> b))
                    throw new IllegalArgumentException("node '" + id + "': binding must be an object");
                Map<String, Object> cb = new LinkedHashMap<>();
                cb.put("kind", requireText(b, "kind"));
                cb.put("componentId", requireText(b, "componentId"));
                c.put("binding", cb);
            } else if (children != null) {
                c.put("children", sanitizeNodes(children, ids, count));
            }
            out.add(c);
        }
        return out;
    }

    private static String requireText(Map<?, ?> m, String key) {
        if (m.get(key) instanceof String s && !s.isBlank()) return s;
        throw new IllegalArgumentException("missing or blank '" + key + "'");
    }
}
