package com.gamma.intelligence.investigation;

import java.util.Map;

/**
 * The input to an investigation (AGT-5 P1 slice C): what to investigate and the tool inputs the
 * playbook needs to gather evidence. {@code params} carries the optional analysis-tool arguments
 * (any of: {@code sinceMinutes}, {@code focus}, {@code focusType}/{@code focusId}, {@code pipeline}/
 * {@code batchA}/{@code batchB}, {@code table}/{@code column}) — the playbook runs each tool only
 * when its inputs are present, so an incident with a thin trigger still yields a timeline-grounded
 * Case. Slice E populates this from a triggering Signal; tests build it directly.
 */
public record Incident(String incidentRef, Map<String, Object> triggerSignal, Map<String, Object> params) {

    public Incident {
        params = params == null ? Map.of() : params;
        triggerSignal = triggerSignal == null ? Map.of() : triggerSignal;
    }
}
