package com.gamma.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Core-owned, read-side model-provider settings (S9): the provider, endpoint, and tier→model map an
 * embedded agent module needs to build its LLM gateway/profile, loaded from the assist-settings screen's
 * persisted file (see {@link ModelSettingsStore}).
 *
 * <p><b>Why this lives in core.</b> The assist-settings <em>screen</em> and its rich write-side value
 * object ({@code com.gamma.agent.model.ProviderSettings} + {@code AssistModelSettings#save}) stay in the
 * {@code inspecto-agent} module. This lightweight read-side twin lets {@code inspecto-intelligence} build a
 * gateway from the same on-disk settings <em>without compile-depending on {@code inspecto-agent}</em>
 * (S9 removes that sibling-internal edge, a prerequisite for building the module against an API jar). Agent
 * remains the single writer of the file format; core owns the reader.
 *
 * <p>Tiers are lowercase name strings ("small"/"medium"/"large") on purpose — so core need not drag agent's
 * kernel {@code ModelTier} enum (40+ references) down with it; a consumer that wants one tier just asks for
 * its name. The record never holds an API key — only {@link #apiKeyRef()}, the NAME of the env var /
 * system property that holds it.
 *
 * @param provider       provider id (e.g. "ollama", "llamacpp", "anthropic", …), lower-cased
 * @param baseUrl        endpoint override; required for local providers
 * @param apiKeyRef      env-var / system-property NAME holding the key (hosted providers) — never the key
 * @param models         tier name ("small"/"medium"/"large") → concrete model id
 * @param timeoutSeconds per-request timeout for the underlying client
 */
public record ModelSettings(String provider, String baseUrl, String apiKeyRef,
                            Map<String, String> models, int timeoutSeconds) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final Set<String> LOCAL = Set.of("ollama", "llamacpp");

    public ModelSettings {
        provider = provider == null ? "" : provider.trim().toLowerCase();
        models = (models == null) ? Map.of() : Map.copyOf(models);
        if (timeoutSeconds <= 0) timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    /** Local providers need no API key — only a reachable base URL. */
    public boolean local() {
        return LOCAL.contains(provider);
    }

    /** The model bound to a tier name ("small"/"medium"/"large"), or {@code null} when unmapped. */
    public String model(String tier) {
        return models.get(tier == null ? "" : tier.trim().toLowerCase());
    }

    /**
     * Fallback settings when nothing is persisted yet. Only the local providers (ollama, llamacpp) — the
     * ones an air-gapped intelligence module can actually reach — carry a base URL + model map here; any
     * other provider yields an empty map (the caller then falls back to an offline stub). The full
     * per-provider default table for the settings screen stays in the agent module.
     */
    public static ModelSettings defaults(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase();
        return switch (p) {
            case "ollama" -> new ModelSettings(p, "http://localhost:11434", null,
                    tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b"), DEFAULT_TIMEOUT_SECONDS);
            case "llamacpp" -> new ModelSettings(p, "http://localhost:8080/v1", null,
                    tiers("default", "default", "default"), DEFAULT_TIMEOUT_SECONDS);
            default -> new ModelSettings(p, null, null, Map.of(), DEFAULT_TIMEOUT_SECONDS);
        };
    }

    private static Map<String, String> tiers(String small, String medium, String large) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("small", small);
        m.put("medium", medium);
        m.put("large", large);
        return m;
    }
}
