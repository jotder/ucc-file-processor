package com.gamma.intelligence;

import java.util.Map;

/**
 * Host-side callback sink for a streamed {@code ask} (AGT-5, hardening pass) — the pure,
 * JSON-agnostic port {@link com.gamma.intelligence.spi.IntelligenceAgent#askStream} drives.
 * Mirrors eoiagent's own {@code AnswerSink}, including the artifact callback (S4: plumbed through
 * for a future A2UI render host — no live agent tool produces one yet).
 */
public interface AgentAnswerSink {

    /** One streamed token of the answer's prose. */
    void onToken(String token);

    /**
     * An inline A2UI artifact the agent produced (chart/kpi/table/etc). Default no-op — most
     * answers carry none.
     */
    default void onArtifact(Map<String, Object> artifact) {}

    /** The complete, final answer — always called exactly once, after every token. */
    void onComplete(AgentAskResult result);

    /** The stream failed (including an unknown/closed session) before completing. */
    void onError(String message);
}
