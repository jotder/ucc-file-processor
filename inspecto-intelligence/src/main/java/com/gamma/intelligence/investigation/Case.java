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
}
