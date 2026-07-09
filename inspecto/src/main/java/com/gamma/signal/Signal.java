package com.gamma.signal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.event.Event;
import com.gamma.event.EventType;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lightweight emitted fact — <em>announces, never decides</em> (job-framework §8.1). The glossary §8
 * envelope: a framework-stamped id, a dotted lower-kebab {@code type} ({@code job.run.completed},
 * {@code fraud.suspicious-activity}), the instant, the producing source ref, a correlation id carried
 * across a chain, a {@link Severity}, and a free-form payload.
 *
 * <p>Persistence rides the one event ledger (§19.2): a Signal maps to an {@link Event} of type
 * {@link EventType#SIGNAL} — dotted type / severity / JSON payload in the attributes, correlationId in
 * the first-class field — and reconstructs from it. Payloads are data, never commands.
 */
public record Signal(String signalId, String type, Instant at, String source,
                     String correlationId, Severity severity, Map<String, Object> payload) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    /** The attribute key carrying the dotted signal type (Event.type is the single {@code SIGNAL} constant). */
    public static final String ATTR_TYPE     = "signalType";
    public static final String ATTR_ID       = "signalId";
    public static final String ATTR_SEVERITY = "severity";
    public static final String ATTR_PAYLOAD  = "payload";

    /** Map this Signal to a ledger {@link Event} for persistence via {@code EventLog.emit}. */
    public Event toEvent() {
        String payloadJson;
        try {
            payloadJson = JSON.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            payloadJson = "{}";
        }
        return Event.builder(EventType.SIGNAL)
                .ts(at == null ? System.currentTimeMillis() : at.toEpochMilli())
                .level((severity == null ? Severity.INFO : severity).toEventLevel())
                .source(source)
                .correlationId(correlationId)
                .message(type)
                .attr(ATTR_ID, signalId)
                .attr(ATTR_TYPE, type)
                .attr(ATTR_SEVERITY, (severity == null ? Severity.INFO : severity).name())
                .attr(ATTR_PAYLOAD, payloadJson)
                .build();
    }

    /** Reconstruct a Signal from a ledger {@link Event} of type {@link EventType#SIGNAL}. */
    public static Signal fromEvent(Event e) {
        Map<String, String> a = e.attributes() == null ? Map.of() : e.attributes();
        return new Signal(
                a.get(ATTR_ID),
                a.getOrDefault(ATTR_TYPE, e.type()),
                Instant.ofEpochMilli(e.ts()),
                e.source(),
                e.correlationId(),
                Severity.parse(a.get(ATTR_SEVERITY)),
                parsePayload(a.get(ATTR_PAYLOAD)));
    }

    /** JSON view for the API ({@code at} as ISO-8601). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signalId", signalId);
        m.put("type", type);
        m.put("at", at == null ? null : at.toString());
        m.put("source", source);
        m.put("correlationId", correlationId);
        m.put("severity", severity == null ? Severity.INFO.name() : severity.name());
        m.put("payload", payload == null ? Map.of() : payload);
        return m;
    }

    private static Map<String, Object> parsePayload(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
