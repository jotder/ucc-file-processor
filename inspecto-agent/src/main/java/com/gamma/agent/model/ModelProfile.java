package com.gamma.agent.model;

import com.gamma.agent.kernel.model.ModelTier;

import java.util.EnumMap;
import java.util.Map;

/**
 * A deployment's hardware/model bundle (ported from UCC's {@code AssistProfile}) — the per-environment
 * mapping of each {@link ModelTier} to a concrete Ollama model name, plus the endpoint and a master
 * enable flag.
 *
 * <h3>Abstain-by-default</h3>
 * {@link #enabled} defaults to {@code false}: with the layer off (CI / vanilla), every
 * {@link OllamaModelProvider#available()} returns {@code false}, so no model is contacted. A deployment
 * turns it on explicitly via {@code -Dagentkernel.ollama.enabled=true}.
 *
 * <h3>Selection (system property → upper-snake env var fallback)</h3>
 * <pre>
 *   -Dagentkernel.profile=cpu-only|dev-laptop|production   (default: cpu-only)
 *   -Dagentkernel.ollama.enabled=true|false                (default: false)
 *   -Dagentkernel.ollama.baseUrl=http://host:11434         (default: http://localhost:11434)
 * </pre>
 */
public record ModelProfile(String name, String baseUrl, boolean enabled, Map<ModelTier, String> models) {

    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    public ModelProfile {
        models = (models == null) ? Map.of() : Map.copyOf(models);
    }

    /** The model name bound to a tier, or {@code null} when the tier is unmapped. */
    public String model(ModelTier tier) {
        return models.get(tier);
    }

    /** CPU-only (testing/dev): LARGE collapses to 7B since 14B is impractical on CPU. */
    public static final ModelProfile CPU_ONLY = new ModelProfile(
            "cpu-only", DEFAULT_BASE_URL, false, tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:7b"));

    /** Dev laptop (~4GB GPU): 2-3B on GPU, 7B CPU-spilled, no 14B. */
    public static final ModelProfile DEV_LAPTOP = new ModelProfile(
            "dev-laptop", DEFAULT_BASE_URL, false, tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:7b"));

    /** Production (16GB+ GPU): 7B inline + 14B for the heavy reasoning tier. */
    public static final ModelProfile PRODUCTION = new ModelProfile(
            "production", DEFAULT_BASE_URL, false, tiers("qwen2.5:3b", "qwen2.5:7b", "qwen2.5:14b"));

    private static Map<ModelTier, String> tiers(String small, String medium, String large) {
        EnumMap<ModelTier, String> m = new EnumMap<>(ModelTier.class);
        m.put(ModelTier.SMALL, small);
        m.put(ModelTier.MEDIUM, medium);
        m.put(ModelTier.LARGE, large);
        return m;
    }

    /** The built-in bundle for a name (case-insensitive), defaulting to {@link #CPU_ONLY}. */
    public static ModelProfile byName(String name) {
        if (name == null) return CPU_ONLY;
        return switch (name.trim().toLowerCase()) {
            case "production", "prod" -> PRODUCTION;
            case "dev-laptop", "dev" -> DEV_LAPTOP;
            default -> CPU_ONLY;
        };
    }

    /** Resolve the active profile from system properties / env vars (see class javadoc). */
    public static ModelProfile fromEnvironment() {
        ModelProfile base = byName(prop("agentkernel.profile", null));
        boolean enabled = Boolean.parseBoolean(prop("agentkernel.ollama.enabled", "false"));
        String baseUrl = prop("agentkernel.ollama.baseUrl", DEFAULT_BASE_URL);
        return new ModelProfile(base.name(), baseUrl, enabled, base.models());
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            v = System.getenv(key.toUpperCase().replace('.', '_'));
        }
        return (v == null || v.isBlank()) ? def : v;
    }
}
