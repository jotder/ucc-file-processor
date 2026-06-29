package com.gamma.event;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One immutable operational fact — Layer 1 of the Operational Intelligence Platform
 * ({@code docs/superpowers/specs/2026-06-13-operational-intelligence-roadmap.md}). Per the
 * requirement an {@code EVENT} is <b>append-only, never modified, high-volume, and structured</b>;
 * this record is that fact, and {@link EventStore} is the append-only sink.
 *
 * <h3>Shape</h3>
 * <ul>
 *   <li>{@link #eventId()} — opaque unique id (UUID); ordering is by {@link #ts()}, not id.</li>
 *   <li>{@link #ts()} — event time as epoch milliseconds (UTC).</li>
 *   <li>{@link #level()} — severity ({@link EventLevel}); drives "everything except DEBUG" capture
 *       and {@link EventQuery} severity filtering.</li>
 *   <li>{@link #type()} — a {@link EventType} constant or any custom string (extensible).</li>
 *   <li>{@link #source()} — origin component (logger/class name, e.g. {@code com.gamma.service.SourceService}).</li>
 *   <li>{@link #pipeline()} — owning pipeline name, or {@code null} (service-wide).</li>
 *   <li>{@link #correlationId()} — batch/run id tying related events together, or {@code null}.</li>
 *   <li>{@link #message()} — human-readable summary (never row content).</li>
 *   <li>{@link #attributes()} — structured detail (rows, durationMs, file, …); immutable, never {@code null}.</li>
 * </ul>
 *
 * <p>Build via {@link #builder(String)} (ergonomic, optional fields) or {@link #log} (the capture path).
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public record Event(String eventId, long ts, EventLevel level, String type, String source,
                    String pipeline, String correlationId, String message,
                    Map<String, String> attributes) {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Canonical constructor — fills sensible defaults and makes {@code attributes} immutable. */
    public Event {
        if (eventId == null || eventId.isBlank()) eventId = UUID.randomUUID().toString();
        if (level == null) level = EventLevel.INFO;
        if (type == null || type.isBlank()) type = EventType.LOG;
        if (source == null) source = "";
        if (message == null) message = "";
        attributes = attributes == null || attributes.isEmpty()
                ? Map.of()
                : Map.copyOf(attributes);
    }

    /** The event time as an ISO-8601 UTC string (e.g. {@code 2026-06-13T08:52:52.123Z}). */
    public String timestamp() {
        return ISO.format(Instant.ofEpochMilli(ts));
    }

    /**
     * JSON-ready view (stable key order) — backs the {@code /events*} API. {@code attributes} is
     * nested verbatim; {@code timestamp} is the human ISO form alongside the raw epoch {@code ts}.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", eventId);
        m.put("ts", ts);
        m.put("timestamp", timestamp());
        m.put("level", level.name());
        m.put("type", type);
        m.put("source", source);
        m.put("pipeline", pipeline);
        m.put("correlationId", correlationId);
        m.put("message", message);
        m.put("attributes", attributes);
        return m;
    }

    // ── construction ─────────────────────────────────────────────────────────────

    /** A captured log record (the {@code EventStoreAppender} path): {@code type = LOG}. */
    public static Event log(long ts, EventLevel level, String source, String message,
                            String pipeline, String correlationId, Map<String, String> attributes) {
        return new Event(null, ts, level, EventType.LOG, source, pipeline, correlationId, message,
                attributes);
    }

    /** Start a builder for a domain event of the given {@code type}; {@code ts} defaults to now. */
    public static Builder builder(String type) {
        return new Builder(type);
    }

    /** Fluent builder for domain emitters — only {@code type} is required. */
    public static final class Builder {
        private final String type;
        private long ts = System.currentTimeMillis();
        private EventLevel level = EventLevel.INFO;
        private String source = "";
        private String pipeline;
        private String correlationId;
        private String message = "";
        private final Map<String, String> attributes = new LinkedHashMap<>();

        private Builder(String type) { this.type = type; }

        public Builder ts(long ts) { this.ts = ts; return this; }
        public Builder level(EventLevel level) { this.level = level; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder pipeline(String pipeline) { this.pipeline = pipeline; return this; }
        public Builder correlationId(String id) { this.correlationId = id; return this; }
        public Builder message(String message) { this.message = message; return this; }

        /** Add one structured attribute; {@code null} value is ignored. */
        public Builder attr(String key, Object value) {
            if (key != null && value != null) attributes.put(key, String.valueOf(value));
            return this;
        }

        // ── audit anatomy (type = EventType.AUDIT) — convenience over attr(); see AuditAttrs ──
        /** Who acted (defaults to {@code appUser} at the edge when absent). */
        public Builder actor(String actor) { return attr(AuditAttrs.ACTOR, actor); }
        /** Actor kind ({@code user}/{@code system}/{@code api_key}/…). */
        public Builder actorType(String actorType) { return attr(AuditAttrs.ACTOR_TYPE, actorType); }
        /** What happened, as a dotted action name (e.g. {@code pipeline.deleted}). */
        public Builder action(String action) { return attr(AuditAttrs.ACTION, action); }
        /** Coarse action class ({@code data_mutation}/{@code destructive}/{@code export}/…). */
        public Builder actionCategory(String category) { return attr(AuditAttrs.ACTION_CATEGORY, category); }
        /** The resource acted upon. */
        public Builder target(String type, String id) {
            return attr(AuditAttrs.TARGET_TYPE, type).attr(AuditAttrs.TARGET_ID, id);
        }
        /** Originating client IP. */
        public Builder ip(String ip) { return attr(AuditAttrs.IP, ip); }
        /** Originating request {@code User-Agent}. */
        public Builder userAgent(String ua) { return attr(AuditAttrs.USER_AGENT, ua); }

        public Event build() {
            return new Event(null, ts, level, type, source, pipeline, correlationId, message, attributes);
        }
    }
}
