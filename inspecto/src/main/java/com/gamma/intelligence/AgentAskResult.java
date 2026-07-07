package com.gamma.intelligence;

import java.util.List;

/**
 * A complete answer from the embedded intelligence agent (AGT-5, P0) — the wire shape of
 * {@code POST /agent/sessions/{id}/ask}. A pure, JSON-serializable record in the lean core; the
 * model that produces it lives in the optional {@code file-processor-intelligence} module.
 *
 * @param kind             one of {@code TEXT}, {@code NAVIGATION}, {@code CLARIFICATION},
 *                          {@code ERROR} (mirrors the platform's {@code AnswerKind}; P0 never
 *                          returns {@code INLINE_ARTIFACT} — that's a P2 authoring capability)
 * @param text             the prose answer
 * @param citations        grounding sources backing the answer, if any
 * @param navigationTarget the catalog {@code pageId} to route to, or {@code null} when {@code kind}
 *                          is not {@code NAVIGATION}
 */
public record AgentAskResult(String kind, String text, List<Citation> citations, String navigationTarget) {

    public AgentAskResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** A grounding source backing part of an answer (a doc anchor or tool-produced fact). */
    public record Citation(String source, String locator) {}
}
