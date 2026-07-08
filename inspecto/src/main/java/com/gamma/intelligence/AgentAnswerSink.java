package com.gamma.intelligence;

/**
 * Host-side callback sink for a streamed {@code ask} (AGT-5, hardening pass) — the pure,
 * JSON-agnostic port {@link com.gamma.intelligence.spi.IntelligenceAgent#askStream} drives.
 * Mirrors eoiagent's own {@code AnswerSink}, minus the artifact callback (P0 never returns
 * {@code INLINE_ARTIFACT} answers).
 */
public interface AgentAnswerSink {

    /** One streamed token of the answer's prose. */
    void onToken(String token);

    /** The complete, final answer — always called exactly once, after every token. */
    void onComplete(AgentAskResult result);

    /** The stream failed (including an unknown/closed session) before completing. */
    void onError(String message);
}
