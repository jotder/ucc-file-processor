package com.gamma.agent.kernel.model;

import com.gamma.agent.kernel.error.ModelError;

/**
 * The seam between a capability and a language model. Implementations are swappable: a local Ollama
 * provider (ring-2 {@code agent-provider-ollama}), a deterministic fake for CPU-only tests
 * ({@code agent-eval}), or a hosted provider behind the same seam.
 *
 * <h3>Abstain-safe contract</h3>
 * {@link #available()} must be a <b>cheap, side-effect-free</b> configuration check — never a network
 * round-trip. Callers always test {@link #available()} before {@link #generate}, so an unconfigured
 * deployment performs no model I/O and capabilities degrade gracefully to "model unavailable".
 */
public interface ModelProvider {

    /** Short identifier for logs/diagnostics (e.g. {@code "ollama:qwen2.5:7b (MEDIUM)"}). */
    String name();

    /** Whether this provider is configured and may be called. Cheap; no network. */
    boolean available();

    /**
     * Generate a completion. Only legal when {@link #available()} is {@code true}; callers must check
     * first. Throws {@link ModelError} if the underlying call fails.
     */
    ModelResponse generate(ModelRequest request);

    /** A provider that is never available and throws {@link ModelError} on use — the safe fallback. */
    static ModelProvider unavailable(String why) {
        return new ModelProvider() {
            @Override public String name() { return "unavailable"; }
            @Override public boolean available() { return false; }
            @Override public ModelResponse generate(ModelRequest request) {
                throw new ModelError("model unavailable: " + why);
            }
        };
    }
}
