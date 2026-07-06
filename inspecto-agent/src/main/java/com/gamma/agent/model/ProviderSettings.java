package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelTier;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One deployment's model-provider configuration (v4.1): which provider serves the assist skills,
 * where it lives, and which concrete model each {@link ModelTier} maps to. This is the value object
 * behind {@code GET/POST /assist/settings}; persistence is {@link AssistModelSettings}, construction
 * of the actual router is {@link ModelProviderFactory}.
 *
 * <h3>Secrets</h3>
 * The record never holds an API key — only {@link #apiKeyRef()}, the <em>name</em> of the environment
 * variable (or system property) that holds it. Raw keys submitted over the API live in the in-memory
 * session store ({@link AssistModelSettings#setSessionKey}) and are never persisted or echoed.
 *
 * @param provider       provider id: one of {@link #knownProviders()} ("anthropic", "openai",
 *                       "gemini", "grok", "ollama", "llamacpp")
 * @param baseUrl        endpoint override; required for local providers, optional for hosted
 * @param apiKeyRef      env-var / system-property name holding the API key (hosted providers)
 * @param models         tier → concrete model id
 * @param timeoutSeconds per-request timeout for the underlying client
 */
public record ProviderSettings(String provider, String baseUrl, String apiKeyRef,
                               Map<ModelTier, String> models, int timeoutSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /** Ordered provider ids: hosted first, then local. */
    private static final List<String> KNOWN =
            List.of("anthropic", "openai", "gemini", "grok", "ollama", "llamacpp");
    private static final Set<String> LOCAL = Set.of("ollama", "llamacpp");

    public ProviderSettings {
        provider = provider == null ? "" : provider.trim().toLowerCase();
        models = (models == null) ? Map.of() : Map.copyOf(models);
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    /** All provider ids this layer understands (hosted ones still need the hosted module). */
    public static Set<String> knownProviders() {
        return new LinkedHashSet<>(KNOWN);
    }

    /** Local providers need no API key — only a reachable base URL. */
    public boolean local() {
        return LOCAL.contains(provider);
    }

    /** The model bound to a tier, or {@code null} when unmapped. */
    public String model(ModelTier tier) {
        return models.get(tier);
    }

    /** Sensible defaults for a provider: tier map, key ref, and base URL. */
    public static ProviderSettings defaults(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        return new ProviderSettings(p, defaultBaseUrl(p), defaultApiKeyRef(p),
                defaultModels(p), DEFAULT_TIMEOUT_SECONDS);
    }

    /** The conventional env var for a hosted provider's key; {@code null} for local providers. */
    public static String defaultApiKeyRef(String provider) {
        return switch (provider) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "gemini" -> "GOOGLE_API_KEY";
            case "grok" -> "XAI_API_KEY";
            default -> null;
        };
    }

    /** Default endpoint: localhost for local providers, the OpenAI-compatible xAI URL for Grok. */
    public static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "ollama" -> "http://localhost:11434";
            case "llamacpp" -> "http://localhost:8080/v1";
            case "grok" -> "https://api.x.ai/v1";
            default -> null;
        };
    }

    /** Default tier → model map per provider (free-text overridable from the settings screen). */
    public static Map<ModelTier, String> defaultModels(String provider) {
        return switch (provider) {
            case "anthropic" -> tiers("claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-8");
            case "openai" -> tiers("gpt-4o-mini", "gpt-4o", "gpt-4o");
            case "gemini" -> tiers("gemini-2.5-flash", "gemini-2.5-flash", "gemini-2.5-pro");
            case "grok" -> tiers("grok-3-mini", "grok-3", "grok-4");
            case "ollama" -> tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b");
            // llama.cpp's server hosts a single model; the name is what the server was started with.
            case "llamacpp" -> tiers("default", "default", "default");
            default -> Map.of();
        };
    }

    private static Map<ModelTier, String> tiers(String small, String medium, String large) {
        EnumMap<ModelTier, String> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.SMALL, small);
        m.put(ModelTier.MEDIUM, medium);
        m.put(ModelTier.LARGE, large);
        return m;
    }
}
