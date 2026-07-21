package com.gamma.intelligence.investigation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One investigation record (AGT-5 P1 slice D): what triggered it, what the RCA playbook (slice C)
 * saw and concluded, and any fix draft it produced. {@code outcome} is a short free-text verdict
 * ({@code "open"} until a playbook run completes); {@code fixDraftRefs} are {@code ComponentStore}
 * ids of DRAFT components written by the fix-draft step (P1 stays L1 — draft, never apply).
 */
public record Case(
        String id,
        String incidentRef,
        Map<String, Object> triggerSignal,
        List<Map<String, Object>> timeline,
        List<Map<String, Object>> hypotheses,
        String outcome,
        List<String> fixDraftRefs,
        Instant createdAt) {

    /** The {@code GET /agent/cases*} view — a plain, JSON-friendly map (core stays free of this type). */
    public Map<String, Object> toView() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("incidentRef", incidentRef);
        m.put("triggerSignal", triggerSignal);
        m.put("timeline", timeline);
        m.put("hypotheses", hypotheses);
        m.put("outcome", outcome);
        m.put("fixDraftRefs", fixDraftRefs);
        m.put("createdAt", createdAt.toString());
        return m;
    }

    /** The persisted shape (AGT-5 P5 durable {@code CaseStore}) — identical to {@link #toView()}. */
    Map<String, Object> toRecord() { return toView(); }

    /** Rehydrate from a persisted {@link #toRecord()} map (restart survival + the recall corpus). */
    @SuppressWarnings("unchecked")
    static Case fromRecord(Map<String, Object> m) {
        return new Case(
                (String) m.get("id"),
                (String) m.get("incidentRef"),
                (Map<String, Object>) orEmptyMap(m.get("triggerSignal")),
                (List<Map<String, Object>>) orEmptyList(m.get("timeline")),
                (List<Map<String, Object>>) orEmptyList(m.get("hypotheses")),
                (String) m.get("outcome"),
                (List<String>) orEmptyList(m.get("fixDraftRefs")),
                Instant.parse((String) m.get("createdAt")));
    }

    /**
     * The symptom text used for similarity recall (AGT-5 P5) — the signal type + subject + message from
     * {@link #triggerSignal}, the outcome, and each hypothesis title/text. Deterministic and dependency-
     * free; {@link CaseSimilarity} tokenizes it. A Case with a richer trigger/hypotheses yields a
     * richer fingerprint, so recall favours genuinely-similar incidents over merely same-type ones.
     */
    public String symptomText() {
        StringBuilder sb = new StringBuilder();
        appendSignal(sb, triggerSignal);
        if (outcome != null) sb.append(' ').append(outcome);
        if (hypotheses != null) {
            for (Map<String, Object> h : hypotheses) {
                appendIfString(sb, h.get("title"));
                appendIfString(sb, h.get("hypothesis"));
                appendIfString(sb, h.get("cause"));
                appendIfString(sb, h.get("summary"));
            }
        }
        return sb.toString();
    }

    private static void appendSignal(StringBuilder sb, Map<String, Object> signal) {
        if (signal == null) return;
        appendIfString(sb, signal.get("type"));
        appendIfString(sb, signal.get("message"));
        Object subject = signal.get("subject");
        if (subject instanceof Map<?, ?> ref) {
            appendIfString(sb, ref.get("kind"));
            appendIfString(sb, ref.get("id"));
        }
        Object payload = signal.get("payload");
        if (payload instanceof Map<?, ?> p) {
            p.keySet().forEach(k -> appendIfString(sb, k));
            appendIfString(sb, p.get("expectation"));
        }
    }

    private static void appendIfString(StringBuilder sb, Object v) {
        if (v != null) sb.append(' ').append(v);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> orEmptyMap(Object v) {
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
    }

    private static List<?> orEmptyList(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }
}
