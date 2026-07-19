package com.gamma.signal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed metadata-graph edge (job-framework §8.1; {@code openapi-v1.json} {@code Ref}) — the shape of
 * a {@link Signal}'s {@code source}/{@code subject}/{@code actor}: what kind of thing, which one, and
 * (optionally) how it relates ({@code binds · tiles · renders · projects · loads · triggers · emits ·
 * invokes}) and via what.
 */
public record Ref(String kind, String id, String rel, String via) {

    /** A Ref with no {@code rel}/{@code via} — the common case for a plain pointer. */
    public static Ref of(String kind, String id) {
        return new Ref(kind, id, null, null);
    }

    /** Parse the legacy {@code kind:id} compact source-string convention (best effort; {@code kind}
     *  is {@code null} when the string has no colon). Returns {@code null} for blank input. */
    public static Ref parseCompact(String s) {
        if (s == null || s.isBlank()) return null;
        int i = s.indexOf(':');
        return i < 0 ? new Ref(null, s, null, null) : new Ref(s.substring(0, i), s.substring(i + 1), null, null);
    }

    /** JSON view for the API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", id);
        if (rel != null) m.put("rel", rel);
        if (via != null) m.put("via", via);
        return m;
    }
}
