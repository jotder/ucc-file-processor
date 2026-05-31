package com.gamma.agent.model;

/**
 * The seam between a skill and a language model (v3.3.0). Implementations are swappable per the
 * provider principle: {@link OllamaModelProvider} for local-first inference, a deterministic fake
 * for CPU-only tests, and (in a later, connected build) a hosted provider behind the same seam.
 *
 * <h3>Abstain-safe contract</h3>
 * {@link #available()} must be a <b>cheap, side-effect-free</b> check (configuration only — never
 * a network round-trip). Callers always test {@link #available()} before {@link #generate}, so an
 * unconfigured deployment (e.g. CI with no Ollama) performs no model I/O and skills degrade
 * gracefully to "model unavailable". This is what lets the agent module's tests — which discover
 * providers via {@code ServiceLoader} — run with no GPU/Ollama present.
 */
public interface ModelProvider {

    /** Short identifier for logs/diagnostics (e.g. {@code "ollama:qwen2.5:7b (MEDIUM)"}). */
    String name();

    /** Whether this provider is configured and may be called. Cheap; no network. */
    boolean available();

    /**
     * Generate a completion. Only legal when {@link #available()} is {@code true}; callers must
     * check first. May throw a {@link RuntimeException} if the underlying call fails (the caller
     * — a skill — catches it and reports the model unavailable).
     */
    String generate(ModelRequest request);

    /** A provider that is never available and throws on use — the safe fallback for an unmapped tier. */
    static ModelProvider unavailable(String why) {
        return new ModelProvider() {
            @Override public String name() { return "unavailable"; }
            @Override public boolean available() { return false; }
            @Override public String generate(ModelRequest request) {
                throw new IllegalStateException("model unavailable: " + why);
            }
        };
    }
}
