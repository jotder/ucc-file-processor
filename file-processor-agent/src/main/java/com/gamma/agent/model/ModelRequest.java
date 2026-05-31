package com.gamma.agent.model;

/**
 * One generation request to a {@link ModelProvider} (v3.3.0): the {@link ModelTier} that picks
 * the model, an optional {@code system} prompt, the {@code prompt} itself, and whether the model
 * should be constrained to emit JSON (Ollama's native {@code format=json}).
 *
 * <p>Grammar-/format-constrained output is what makes even small models shape-reliable, so the
 * structured-output skills (and the AI describer) request {@code json}.
 */
public record ModelRequest(ModelTier tier, String system, String prompt, boolean jsonFormat) {

    /** Free-text generation at the given tier. */
    public static ModelRequest text(ModelTier tier, String system, String prompt) {
        return new ModelRequest(tier, system, prompt, false);
    }

    /** JSON-constrained generation at the given tier (Ollama {@code format=json}). */
    public static ModelRequest json(ModelTier tier, String system, String prompt) {
        return new ModelRequest(tier, system, prompt, true);
    }
}
