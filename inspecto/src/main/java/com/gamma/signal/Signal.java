package com.gamma.signal;

import com.gamma.event.Event;
import com.gamma.event.EventType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical emitted fact — <em>announces, never decides</em> (job-framework §8.1; the shape
 * {@code openapi-v1.json} already specifies). A framework-stamped id, a dotted-canonical {@code type}
 * ({@code job.run.completed}, {@code fraud.suspicious-activity}), the instant, a {@link Severity}
 * (six levels), a typed {@link Ref} {@code source} (who emitted), an optional {@link Ref}
 * {@code subject} (what it's about), the correlation/causation chain, the owning {@code space}, an
 * optional {@link Ref} {@code actor} (who acted), a human {@code message}, a structured {@code payload}
 * and a per-type {@code schemaVersion}. Payloads are data, never commands.
 *
 * <p>Persistence rides the one event ledger (§19.2): a Signal maps to an {@link Event} of type
 * {@link EventType#SIGNAL} — the dotted type / severity / correlation-causation-space / {@code Ref}
 * fields live in attributes (the legacy flat-String-attribute view), while {@code payload} rides the
 * native {@link Event#payload()} column (no JSON-in-a-string) — and reconstructs from it losslessly.
 */
public record Signal(String signalId, String type, Instant at, Severity severity, Ref source,
                     Ref subject, String correlationId, String causationId, String space,
                     Ref actor, String message, Map<String, Object> payload, int schemaVersion) {

    /** Fills framework-stamped defaults: a random {@code signalId} when absent, {@code at = now},
     *  {@code severity = INFO}, {@code message = type}, an empty {@code payload}, {@code schemaVersion = 1}. */
    public Signal {
        if (signalId == null || signalId.isBlank()) signalId = UUID.randomUUID().toString();
        if (at == null) at = Instant.now();
        if (severity == null) severity = Severity.INFO;
        if (message == null || message.isBlank()) message = type;
        payload = payload == null ? Map.of() : payload;
        if (schemaVersion <= 0) schemaVersion = 1;
    }

    /** The attribute key carrying the dotted signal type (Event.type is the single {@code SIGNAL} constant). */
    public static final String ATTR_TYPE      = "signalType";
    public static final String ATTR_ID        = "signalId";
    public static final String ATTR_SEVERITY  = "severity";
    public static final String ATTR_CAUSATION = "causationId";
    public static final String ATTR_SPACE     = "space";
    public static final String ATTR_SCHEMA_VERSION = "schemaVersion";

    private static final String SRC_KIND = "source.kind", SRC_ID = "source.id", SRC_REL = "source.rel", SRC_VIA = "source.via";
    private static final String SUBJ_KIND = "subject.kind", SUBJ_ID = "subject.id", SUBJ_REL = "subject.rel", SUBJ_VIA = "subject.via";
    private static final String ACTOR_KIND = "actor.kind", ACTOR_ID = "actor.id", ACTOR_REL = "actor.rel", ACTOR_VIA = "actor.via";

    /** Map this Signal to a ledger {@link Event} for persistence via {@code EventLog.emit}. The
     *  {@link Event#source()} legacy String is a {@code kind:id} compact form of {@link #source} for
     *  free-text search / {@code EventQuery.textContains}; the full {@code Ref} rides the attributes. */
    public Event toEvent() {
        Event.Builder b = Event.builder(EventType.SIGNAL)
                .ts(at.toEpochMilli())
                .level(severity.toEventLevel())
                .source(compact(source))
                .correlationId(correlationId)
                .message(message)
                .payload(payload)
                .attr(ATTR_ID, signalId)
                .attr(ATTR_TYPE, type)
                .attr(ATTR_SEVERITY, severity.name())
                .attr(ATTR_CAUSATION, causationId)
                .attr(ATTR_SPACE, space)
                .attr(ATTR_SCHEMA_VERSION, schemaVersion);
        attrRef(b, source, SRC_KIND, SRC_ID, SRC_REL, SRC_VIA);
        attrRef(b, subject, SUBJ_KIND, SUBJ_ID, SUBJ_REL, SUBJ_VIA);
        attrRef(b, actor, ACTOR_KIND, ACTOR_ID, ACTOR_REL, ACTOR_VIA);
        return b.build();
    }

    /** Reconstruct a Signal from a ledger {@link Event} of type {@link EventType#SIGNAL} — a true
     *  inverse of {@link #toEvent()} (round-trips losslessly, including the structured payload). */
    public static Signal fromEvent(Event e) {
        Map<String, String> a = e.attributes() == null ? Map.of() : e.attributes();
        return new Signal(
                a.get(ATTR_ID),
                a.getOrDefault(ATTR_TYPE, e.type()),
                Instant.ofEpochMilli(e.ts()),
                Severity.parse(a.get(ATTR_SEVERITY)),
                refOf(a, SRC_KIND, SRC_ID, SRC_REL, SRC_VIA, e.source()),
                refOf(a, SUBJ_KIND, SUBJ_ID, SUBJ_REL, SUBJ_VIA, null),
                e.correlationId(),
                a.get(ATTR_CAUSATION),
                a.get(ATTR_SPACE),
                refOf(a, ACTOR_KIND, ACTOR_ID, ACTOR_REL, ACTOR_VIA, null),
                e.message(),
                e.payload(),
                parseSchemaVersion(a.get(ATTR_SCHEMA_VERSION)));
    }

    /** JSON view for the API ({@code at} as ISO-8601; {@code severity} lowercase per the wire spec;
     *  {@code source}/{@code subject}/{@code actor} as nested {@link Ref} objects, not flat strings). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signalId", signalId);
        m.put("type", type);
        m.put("at", at.toString());
        m.put("severity", severity.name().toLowerCase(java.util.Locale.ROOT));
        m.put("source", source == null ? null : source.toMap());
        m.put("subject", subject == null ? null : subject.toMap());
        m.put("correlationId", correlationId);
        m.put("causationId", causationId);
        m.put("space", space);
        m.put("actor", actor == null ? null : actor.toMap());
        m.put("message", message);
        m.put("payload", payload);
        m.put("schemaVersion", schemaVersion);
        return m;
    }

    private static void attrRef(Event.Builder b, Ref r, String kindKey, String idKey, String relKey, String viaKey) {
        if (r == null) return;
        b.attr(kindKey, r.kind()).attr(idKey, r.id()).attr(relKey, r.rel()).attr(viaKey, r.via());
    }

    /** Reconstruct a {@link Ref} from its {@code <prefix>.kind/id/rel/via} attributes; falls back to
     *  parsing {@code fallbackCompact} (the legacy flat {@code Event.source()} string) when absent, so
     *  events persisted before this attribute encoding still yield a best-effort {@code Ref}. */
    private static Ref refOf(Map<String, String> a, String kindKey, String idKey, String relKey, String viaKey,
                             String fallbackCompact) {
        String kind = a.get(kindKey), id = a.get(idKey);
        if (kind == null && id == null) return Ref.parseCompact(fallbackCompact);
        return new Ref(kind, id, a.get(relKey), a.get(viaKey));
    }

    private static int parseSchemaVersion(String s) {
        try {
            return s == null ? 1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The legacy flat {@code kind:id} source string (or just {@code id}/{@code kind} when the other is
     *  absent) — kept so {@code EventQuery.textContains} can still free-text search a Signal's source. */
    private static String compact(Ref r) {
        if (r == null) return null;
        if (r.kind() == null) return r.id();
        return r.id() == null ? r.kind() : r.kind() + ":" + r.id();
    }
}
