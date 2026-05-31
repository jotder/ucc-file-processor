package com.gamma.agent;

import com.gamma.assist.AssistResult;

import java.util.Set;

/**
 * One audit record per assist call (v3.3.0). M3 is read-only, so every event is a <em>suggestion</em>
 * — nothing is applied — but the trail is the same seam M4+ will extend when skills become
 * write-bearing (recording approver + applied diff). Emitted through an injectable sink on
 * {@link UccAssistAgent} so it is both logged and unit-observable.
 *
 * @param intent        the skill id invoked
 * @param status        the result status (OK / UNSUPPORTED / UNAVAILABLE)
 * @param citationCount how many grounding sources backed the answer
 * @param durationMs    wall-clock of the call
 * @param contextKeys   the screen-context keys supplied (not the values — no data-plane content)
 */
public record AuditEvent(String intent, AssistResult.Status status,
                         int citationCount, long durationMs, Set<String> contextKeys) {
}
