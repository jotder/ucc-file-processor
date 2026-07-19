package com.gamma.intelligence;

import java.util.List;
import java.util.Map;

/**
 * A complete answer from the embedded intelligence agent (AGT-5, P0) — the wire shape of
 * {@code POST /agent/sessions/{id}/ask}. A pure, JSON-serializable record in the lean core; the
 * model that produces it lives in the optional {@code file-processor-intelligence} module.
 *
 * @param kind             one of {@code TEXT}, {@code NAVIGATION}, {@code CLARIFICATION},
 *                          {@code ERROR}, {@code INLINE_ARTIFACT} (mirrors the platform's
 *                          {@code AnswerKind})
 * @param text             the prose answer
 * @param citations        grounding sources backing the answer, if any
 * @param navigationTarget the catalog {@code pageId} to route to, or {@code null} when {@code kind}
 *                          is not {@code NAVIGATION}
 * @param artifact         the A2UI artifact JSON ({@code {"kind": "chart"|"kpi"|"text"|"data-table", ...}}),
 *                          or {@code null} when the answer carries none
 */
public record AgentAskResult(String kind, String text, List<Citation> citations, String navigationTarget,
                              Map<String, Object> artifact) {

    public AgentAskResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** A grounding source backing part of an answer (a doc anchor or tool-produced fact). */
    public record Citation(String source, String locator) {}
}
