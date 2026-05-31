package com.gamma.agent.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves a {@link ModelTier} to the {@link ModelProvider} that serves it (v3.3.0). This is the
 * one place a skill's tier becomes a concrete model — so re-tiering a deployment is a profile
 * change, not a code change.
 *
 * <p>Build it from the environment ({@link #fromEnvironment()}) in production, from an explicit
 * {@link AssistProfile} in scenario tests, or from a single provider ({@link #of(ModelProvider)})
 * to inject a deterministic fake in CPU-only golden tests. An unmapped tier resolves to a safe
 * {@link ModelProvider#unavailable(String)} rather than {@code null}.
 */
public final class ModelRouter {

    private final Map<ModelTier, ModelProvider> byTier;

    public ModelRouter(Map<ModelTier, ModelProvider> byTier) {
        this.byTier = (byTier == null) ? Map.of() : Map.copyOf(byTier);
    }

    /** The provider for a tier; never {@code null} (falls back to an unavailable provider). */
    public ModelProvider provider(ModelTier tier) {
        return byTier.getOrDefault(tier, ModelProvider.unavailable("no provider for tier " + tier));
    }

    /** True when at least one tier has an available provider. */
    public boolean anyAvailable() {
        return byTier.values().stream().anyMatch(ModelProvider::available);
    }

    /** All tiers served by the same provider — for tests injecting a single fake model. */
    public static ModelRouter of(ModelProvider provider) {
        EnumMap<ModelTier, ModelProvider> m = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) m.put(t, provider);
        return new ModelRouter(m);
    }

    /** Production wiring: an {@link OllamaModelProvider} per tier from the active profile. */
    public static ModelRouter fromEnvironment() {
        return fromProfile(AssistProfile.fromEnvironment());
    }

    /** A router backed by {@link OllamaModelProvider}s for the given profile (one per tier). */
    public static ModelRouter fromProfile(AssistProfile profile) {
        EnumMap<ModelTier, ModelProvider> m = new EnumMap<>(ModelTier.class);
        for (ModelTier t : ModelTier.values()) m.put(t, new OllamaModelProvider(profile, t));
        return new ModelRouter(m);
    }
}
