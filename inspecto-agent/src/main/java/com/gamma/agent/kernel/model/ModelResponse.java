package com.gamma.agent.kernel.model;

/**
 * A model completion plus token usage. Token counts are {@code -1} when the provider does not report
 * them (e.g. the deterministic fake, or a provider without usage metadata). Usage flows into the
 * audit summary ({@code AgentCompleted}) — keys and counts only, never the generated text.
 */
public record ModelResponse(String text, int promptTokens, int completionTokens) {

    /** A response with unknown token usage. */
    public static ModelResponse of(String text) {
        return new ModelResponse(text, -1, -1);
    }
}
