package com.gamma.util;

import java.util.Map;

/**
 * The one dotted-path walker shared by every consumer that addresses a fact by {@code a.b.c} — notification
 * templates ({@code {{payload.rowsOut}}}), job {@code $signal.<path>} binds and {@code when:} guards, and
 * (future) A2UI action params / agent context selectors (event-signal-backbone-plan §4.4). Walks nested
 * {@link Map}s; a single-segment path is just a top-level {@code .get(key)}, so it is a safe drop-in for
 * callers that previously did a flat lookup.
 */
public final class DottedPath {

    private DottedPath() {}

    /** Walk {@code path} ({@code a.b.c}) through nested maps starting at {@code root}; {@code null} if
     *  {@code root} is {@code null} or any hop is missing/not a {@link Map}. */
    public static Object resolve(Map<?, ?> root, String path) {
        if (root == null || path == null) return null;
        Object cur = root;
        for (String key : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = map.get(key);
            if (cur == null) return null;
        }
        return cur;
    }
}
