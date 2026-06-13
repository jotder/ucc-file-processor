package com.gamma.event;

import java.util.Locale;

/**
 * A filter + page over the event store — the query model behind {@code GET /events/search}. Every
 * field is optional except the paging bounds; {@code null} means "no constraint on this dimension".
 * The same instance drives both {@link #matches(Event)} (in-memory filtering, used by
 * {@code InMemoryEventStore} and the live-tail buffer) and SQL {@code WHERE} generation
 * (used by {@code ParquetEventStore} against {@code read_parquet}), so the two backends return the
 * same rows for the same query.
 *
 * @param fromMs        inclusive lower time bound (epoch millis), or {@code null}
 * @param toMs          inclusive upper time bound (epoch millis), or {@code null}
 * @param minLevel      minimum severity ({@code level >= minLevel}), or {@code null}
 * @param type          exact {@link Event#type()} (case-insensitive), or {@code null}
 * @param pipeline      exact {@link Event#pipeline()} (case-insensitive), or {@code null}
 * @param correlationId exact {@link Event#correlationId()}, or {@code null}
 * @param textContains  case-insensitive substring of {@link Event#message()} or {@link Event#source()},
 *                      or {@code null}
 * @param limit         maximum rows to return (clamped to {@code [1, }{@value #MAX_LIMIT}{@code ]})
 * @param offset        rows to skip from the newest (for paging; clamped to {@code >= 0})
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public record EventQuery(Long fromMs, Long toMs, EventLevel minLevel, String type, String pipeline,
                         String correlationId, String textContains, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 10_000;

    /** Clamp paging bounds into range. */
    public EventQuery {
        limit = Math.max(1, Math.min(MAX_LIMIT, limit == 0 ? DEFAULT_LIMIT : limit));
        offset = Math.max(0, offset);
    }

    /** An unfiltered query returning the newest {@code limit} events. */
    public static EventQuery recent(int limit) {
        return new Builder().limit(limit).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@code true} when {@code e} satisfies every set constraint of this query. */
    public boolean matches(Event e) {
        if (fromMs != null && e.ts() < fromMs) return false;
        if (toMs != null && e.ts() > toMs) return false;
        if (minLevel != null && !e.level().atLeast(minLevel)) return false;
        if (type != null && !type.equalsIgnoreCase(e.type())) return false;
        if (pipeline != null && !pipeline.equalsIgnoreCase(e.pipeline())) return false;
        if (correlationId != null && !correlationId.equals(e.correlationId())) return false;
        if (textContains != null && !textContains.isBlank()) {
            String needle = textContains.toLowerCase(Locale.ROOT);
            boolean inMsg = e.message() != null && e.message().toLowerCase(Locale.ROOT).contains(needle);
            boolean inSrc = e.source() != null && e.source().toLowerCase(Locale.ROOT).contains(needle);
            if (!inMsg && !inSrc) return false;
        }
        return true;
    }

    /** Fluent builder; all filters default to "no constraint". */
    public static final class Builder {
        private Long fromMs, toMs;
        private EventLevel minLevel;
        private String type, pipeline, correlationId, textContains;
        private int limit = DEFAULT_LIMIT;
        private int offset = 0;

        public Builder from(Long ms) { this.fromMs = ms; return this; }
        public Builder to(Long ms) { this.toMs = ms; return this; }
        public Builder minLevel(EventLevel l) { this.minLevel = l; return this; }
        public Builder type(String t) { this.type = blankToNull(t); return this; }
        public Builder pipeline(String p) { this.pipeline = blankToNull(p); return this; }
        public Builder correlationId(String c) { this.correlationId = blankToNull(c); return this; }
        public Builder textContains(String q) { this.textContains = blankToNull(q); return this; }
        public Builder limit(int n) { this.limit = n; return this; }
        public Builder offset(int n) { this.offset = n; return this; }

        public EventQuery build() {
            return new EventQuery(fromMs, toMs, minLevel, type, pipeline, correlationId,
                    textContains, limit, offset);
        }

        private static String blankToNull(String s) {
            return (s == null || s.isBlank()) ? null : s.trim();
        }
    }
}
