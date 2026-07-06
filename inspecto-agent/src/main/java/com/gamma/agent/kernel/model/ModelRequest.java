package com.gamma.agent.kernel.model;

/**
 * One generation request to a {@link ModelProvider}: the {@link ModelTier} that selects the model,
 * an optional {@code system} prompt, the {@code prompt}, and whether output should be JSON-constrained
 * (provider-native, e.g. Ollama {@code format=json}). Format-constrained output is what makes small
 * models shape-reliable, so structured-output capabilities request {@link #json}.
 */
public record ModelRequest(ModelTier tier, String system, String prompt, boolean jsonFormat) {

    /** Free-text generation at the given tier. */
    public static ModelRequest text(ModelTier tier, String system, String prompt) {
        return new ModelRequest(tier, system, prompt, false);
    }

    /** JSON-constrained generation at the given tier. */
    public static ModelRequest json(ModelTier tier, String system, String prompt) {
        return new ModelRequest(tier, system, prompt, true);
    }
}
