package com.gamma.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named, reusable event filter — the "Saved Views" feature of the Phase-1 Event Viewer. It stores
 * the same query-parameter keys the {@code GET /events/search} endpoint accepts ({@code level},
 * {@code type}, {@code pipeline}, {@code correlationId}, {@code q}, {@code from}, {@code to}), so a
 * saved view is just a bookmarked search the operator can re-apply with one click.
 *
 * @param name      unique view name (operator-chosen)
 * @param filters   search-param key→value map (only the set keys are present)
 * @param createdAt creation time (epoch millis)
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public record SavedView(String name, Map<String, String> filters, long createdAt) {

    public SavedView {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    /** JSON-ready view (stable key order). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("filters", filters);
        m.put("createdAt", createdAt);
        return m;
    }
}
