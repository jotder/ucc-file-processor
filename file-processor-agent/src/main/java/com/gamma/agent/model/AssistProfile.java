package com.gamma.agent.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * A deployment's hardware/model bundle (v3.3.0, locked V-8) — the per-environment mapping of each
 * {@link ModelTier} to a concrete Ollama model name, plus the endpoint and a master enable flag.
 * Three built-in bundles ship; an operator selects and tunes one through system properties / env
 * vars via {@link #fromEnvironment()}.
 *
 * <h3>Abstain-by-default</h3>
 * {@link #enabled} defaults to {@code false}. With the assist layer off (the CI / vanilla case)
 * every {@link OllamaModelProvider#available()} returns {@code false}, so no model is ever
 * contacted and the agent/describer abstain. A deployment turns the agent on explicitly with
 * {@code -Dassist.enabled=true} (and optionally {@code -Dassist.ollama.baseUrl=…}).
 *
 * <h3>Selection</h3>
 * <pre>
 *   -Dassist.profile=cpu-only|dev-laptop|production   (default: cpu-only)
 *   -Dassist.enabled=true|false                        (default: false)
 *   -Dassist.ollama.baseUrl=http://host:11434          (default: http://localhost:11434)
 * </pre>
 * Each property falls back to the matching upper-snake env var ({@code ASSIST_PROFILE}, …).
 */
public record AssistProfile(String name, String baseUrl, boolean enabled, Map<ModelTier, String> models) {

    /** Default local Ollama endpoint. */
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    public AssistProfile {
        models = (models == null) ? Map.of() : Map.copyOf(models);
    }

    /** The model name bound to a tier in this profile, or {@code null} when the tier is unmapped. */
    public String model(ModelTier tier) {
        return models.get(tier);
    }

    // ── Built-in bundles (V-8). Model names are Ollama tags; an operator can re-tag per site. ──

    /** CPU-only (testing/dev): correctness-complete, latency not a goal; 14B is impractical so LARGE→7B. */
    public static final AssistProfile CPU_ONLY = new AssistProfile(
            "cpu-only", DEFAULT_BASE_URL, false,
            tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:7b"));

    /** Dev laptop (~4GB GPU): 2-3B on GPU, 7B CPU-spilled, no 14B. */
    public static final AssistProfile DEV_LAPTOP = new AssistProfile(
            "dev-laptop", DEFAULT_BASE_URL, false,
            tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:7b"));

    /** Production (16GB+ GPU): 7B inline + 14B for the heavy reasoning tier. */
    public static final AssistProfile PRODUCTION = new AssistProfile(
            "production", DEFAULT_BASE_URL, false,
            tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b"));

    private static Map<ModelTier, String> tiers(String small, String medium, String large) {
        EnumMap<ModelTier, String> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.SMALL, small);
        m.put(ModelTier.MEDIUM, medium);
        m.put(ModelTier.LARGE, large);
        return m;
    }

    /** The built-in bundle for a name (case-insensitive), defaulting to {@link #CPU_ONLY}. */
    public static AssistProfile byName(String name) {
        if (name == null) return CPU_ONLY;
        return switch (name.trim().toLowerCase()) {
            case "production", "prod" -> PRODUCTION;
            case "dev-laptop", "dev" -> DEV_LAPTOP;
            default -> CPU_ONLY;
        };
    }

    /**
     * Resolve the active profile from system properties / env vars (see class javadoc). The
     * selected bundle's model map is kept; {@code enabled} and {@code baseUrl} are overlaid from
     * configuration. Default is {@code cpu-only}, disabled — so a vanilla process never calls a model.
     */
    public static AssistProfile fromEnvironment() {
        AssistProfile base = byName(prop("assist.profile", null));
        boolean enabled = Boolean.parseBoolean(prop("assist.enabled", "false"));
        String baseUrl = prop("assist.ollama.baseUrl", DEFAULT_BASE_URL);
        return new AssistProfile(base.name(), baseUrl, enabled, base.models());
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            v = System.getenv(key.toUpperCase().replace('.', '_'));
        }
        return (v == null || v.isBlank()) ? def : v;
    }
}
