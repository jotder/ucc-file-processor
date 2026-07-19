package com.gamma.signal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure Signal→AG-UI mapping primitive (event-signal-backbone-plan §4.1 table / §4.5 point 4, R-UIGEN).
 * No HTTP/IO here — this is the projection {@code /signals/stream} consumers (S4+) apply to a
 * {@link Signal} to get an AG-UI-shaped event. Per D3 ("AG-UI-shaped, domain-named"), the mapping is
 * data-driven off the domain-canonical dotted {@code type}; internal type names never change.
 *
 * <p><b>Wire shape</b> — a JSON-ready {@code Map<String,Object>}:
 * <pre>{
 *   "type":            AG-UI EventType string (e.g. "RUN_STARTED"),
 *   "threadId":        Signal.correlationId,
 *   "runId":           Signal.correlationId  (same value — AG-UI overloads both onto one run/thread id
 *                                              in this codebase's usage; kept as two keys per the spec table),
 *   "parentMessageId": Signal.causationId,
 *   "raw": {                                  // AG-UI has no equivalent slot for these — domain wins (§4.1)
 *     "signalId":  ..., "domainType": Signal.type (the original dotted string, always present — this
 *                  is how the CUSTOM fallback carries an uncatalogued type),
 *     "severity":  ..., "source": Ref map, "subject": Ref map, "actor": Ref map,
 *     "message":   ..., "payload": Signal.payload, "space": ...
 *   }
 * }</pre>
 * The {@code raw} key name is this projection's own choice (not an AG-UI or domain term) — documented
 * here as the one place it's decided.
 */
public final class AgUiProjection {

    private AgUiProjection() {}

    /** Fallback AG-UI event type for any domain {@code type} with no explicit catalog entry — the
     *  mapping is total; it never throws or drops a signal. */
    public static final String CUSTOM = "CUSTOM";

    /**
     * The {@code agent.*} types S1's {@code EventLogAuditSink} emits, mapped to their AG-UI lifecycle/
     * tool-call counterpart. Exact AG-UI name spelling is a projection-at-the-edge judgement call in
     * this slice (D3) — not an external contract yet.
     */
    private static final Map<String, String> CATALOG = Map.of(
            "agent.run.started",     "RUN_STARTED",
            "agent.run.completed",   "RUN_FINISHED",
            "agent.run.failed",      "RUN_ERROR",
            "agent.tool.called",     "TOOL_CALL_START",
            "agent.tool.completed",  "TOOL_CALL_END",
            "agent.model.called",    "CUSTOM_MODEL_CALLED",
            "agent.human.decided",   "CUSTOM_HUMAN_DECIDED"
    );

    /** Project {@code signal} to its AG-UI-shaped wire map (see class javadoc for the exact shape). */
    public static Map<String, Object> project(Signal signal) {
        String agUiType = CATALOG.getOrDefault(signal.type(), CUSTOM);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", agUiType);
        out.put("threadId", signal.correlationId());
        out.put("runId", signal.correlationId());
        out.put("parentMessageId", signal.causationId());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("signalId", signal.signalId());
        raw.put("domainType", signal.type());
        raw.put("severity", signal.severity().name().toLowerCase(java.util.Locale.ROOT));
        raw.put("source", signal.source() == null ? null : signal.source().toMap());
        raw.put("subject", signal.subject() == null ? null : signal.subject().toMap());
        raw.put("actor", signal.actor() == null ? null : signal.actor().toMap());
        raw.put("message", signal.message());
        raw.put("payload", signal.payload());
        raw.put("space", signal.space());
        out.put("raw", raw);

        return out;
    }
}
