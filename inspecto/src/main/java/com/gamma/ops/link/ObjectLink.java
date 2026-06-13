package com.gamma.ops.link;

import com.gamma.ops.ObjectType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One directed correlation edge between two operational objects — the first-class {@code OBJECT_LINK}
 * of the Operational Intelligence Platform (Phase 4). Where Phases 2–3 correlated lightly via
 * {@link com.gamma.ops.OperationalObject#correlationId()}, a {@link ObjectType#CASE} makes
 * relationships explicit and traversable: {@code Case CONTAINS Issue}, {@code Issue ESCALATED_FROM
 * Alert}, {@code Alert CAUSED_BY Event}. The shape mirrors the requirement's link model
 * {@code {from, from_type, to, to_type, relationship}}.
 *
 * <p>Like an {@link com.gamma.event.Event}, a link is an <b>immutable, append-only fact</b> — created
 * and queried, never mutated — so its store ({@link LinkStore}) has no {@code update}. The
 * {@link #relationship()} is normalised to upper-case (see {@link LinkRelationship}) so matching is
 * case-insensitive; the endpoints carry their {@link ObjectType} so a rendered graph needs no extra
 * lookup.
 *
 * @since 4.5.0
 */
@com.gamma.api.PublicApi(since = "4.5.0")
public record ObjectLink(String fromId, ObjectType fromType, String toId, ObjectType toType,
                         String relationship, long createdAt) {

    /** Canonical constructor — validates the endpoints and normalises the relationship. */
    public ObjectLink {
        if (fromId == null || fromId.isBlank()) throw new IllegalArgumentException("link fromId is required");
        if (toId == null || toId.isBlank()) throw new IllegalArgumentException("link toId is required");
        if (fromType == null || toType == null) throw new IllegalArgumentException("link endpoint types are required");
        relationship = norm(relationship);
        if (relationship == null) throw new IllegalArgumentException("link relationship is required");
    }

    /** A link from {@code from} to {@code to} via {@code relationship}, stamped now. */
    public static ObjectLink of(String fromId, ObjectType fromType, String toId, ObjectType toType,
                                String relationship) {
        return new ObjectLink(fromId, fromType, toId, toType, relationship, System.currentTimeMillis());
    }

    /** Given one endpoint id, the id at the other end of this edge (or {@code null} if {@code id} is neither). */
    public String other(String id) {
        if (fromId.equals(id)) return toId;
        if (toId.equals(id)) return fromId;
        return null;
    }

    /** JSON-ready view (stable key order) — backs the {@code /objects/{id}/links} + graph API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", fromId);
        m.put("fromType", fromType.name());
        m.put("to", toId);
        m.put("toType", toType.name());
        m.put("relationship", relationship);
        m.put("createdAt", createdAt);
        return m;
    }

    private static String norm(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
