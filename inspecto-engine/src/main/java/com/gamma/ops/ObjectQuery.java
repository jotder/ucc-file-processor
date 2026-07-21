package com.gamma.ops;

import java.util.Locale;

/**
 * A filter + page over the {@link ObjectStore} — the query model behind {@code GET /objects}. Every
 * field is optional except the paging bounds; {@code null} means "no constraint on this dimension".
 * Mirrors {@link com.gamma.event.EventQuery}: the same instance drives both {@link #matches} (the
 * in-memory store) and SQL {@code WHERE} generation ({@code DbObjectStore}), so both backends return
 * the same rows.
 *
 * @param objectType    exact {@link ObjectType}, or {@code null}
 * @param status        exact {@link OperationalObject#status()} (case-insensitive), or {@code null}
 * @param severity      exact {@link OperationalObject#severity()} (case-insensitive), or {@code null}
 * @param assignee      exact {@link OperationalObject#assignee()} (case-insensitive), or {@code null}
 * @param owner         exact {@link OperationalObject#owner()} (case-insensitive), or {@code null}
 * @param correlationId exact {@link OperationalObject#correlationId()}, or {@code null}
 * @param textContains  case-insensitive substring of {@code title} or {@code description}, or {@code null}
 * @param limit         maximum rows to return (clamped to {@code [1, }{@value #MAX_LIMIT}{@code ]})
 * @param offset        rows to skip from the newest (paging; clamped to {@code >= 0})
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.3.0")
public record ObjectQuery(ObjectType objectType, String status, String severity, String assignee,
                          String owner, String correlationId, String textContains,
                          int limit, int offset) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 10_000;

    /** Clamp paging bounds into range. */
    public ObjectQuery {
        limit = Math.max(1, Math.min(MAX_LIMIT, limit == 0 ? DEFAULT_LIMIT : limit));
        offset = Math.max(0, offset);
    }

    /** An unfiltered query returning the newest {@code limit} objects. */
    public static ObjectQuery recent(int limit) {
        return new Builder().limit(limit).build();
    }

    /** This query's filters with the paging bounds widened to all matches ({@link #MAX_LIMIT}, offset 0) —
     *  the ordered source set a caller slices itself (keyset pagination, scope-filtered analytics). */
    public ObjectQuery unbounded() {
        return new ObjectQuery(objectType, status, severity, assignee, owner, correlationId, textContains,
                MAX_LIMIT, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@code true} when {@code o} satisfies every set constraint of this query. */
    public boolean matches(OperationalObject o) {
        if (objectType != null && o.objectType() != objectType) return false;
        if (status != null && !status.equalsIgnoreCase(o.status())) return false;
        if (severity != null && !severity.equalsIgnoreCase(o.severity())) return false;
        if (assignee != null && !assignee.equalsIgnoreCase(o.assignee())) return false;
        if (owner != null && !owner.equalsIgnoreCase(o.owner())) return false;
        if (correlationId != null && !correlationId.equals(o.correlationId())) return false;
        if (textContains != null && !textContains.isBlank()) {
            String needle = textContains.toLowerCase(Locale.ROOT);
            boolean inTitle = o.title() != null && o.title().toLowerCase(Locale.ROOT).contains(needle);
            boolean inDesc = o.description() != null && o.description().toLowerCase(Locale.ROOT).contains(needle);
            if (!inTitle && !inDesc) return false;
        }
        return true;
    }

    /** Fluent builder; all filters default to "no constraint". */
    public static final class Builder {
        private ObjectType objectType;
        private String status, severity, assignee, owner, correlationId, textContains;
        private int limit = DEFAULT_LIMIT;
        private int offset = 0;

        public Builder objectType(ObjectType t) { this.objectType = t; return this; }
        public Builder status(String s) { this.status = blankToNull(s); return this; }
        public Builder severity(String s) { this.severity = blankToNull(s); return this; }
        public Builder assignee(String s) { this.assignee = blankToNull(s); return this; }
        public Builder owner(String s) { this.owner = blankToNull(s); return this; }
        public Builder correlationId(String c) { this.correlationId = blankToNull(c); return this; }
        public Builder textContains(String q) { this.textContains = blankToNull(q); return this; }
        public Builder limit(int n) { this.limit = n; return this; }
        public Builder offset(int n) { this.offset = n; return this; }

        public ObjectQuery build() {
            return new ObjectQuery(objectType, status, severity, assignee, owner, correlationId,
                    textContains, limit, offset);
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s.trim();
        }
    }
}
