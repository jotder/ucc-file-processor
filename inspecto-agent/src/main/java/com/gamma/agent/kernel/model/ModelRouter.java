package com.gamma.agent.kernel.model;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves a {@link ModelTier} to the {@link ModelProvider} that serves it — the one place a tier
 * becomes a concrete model, so re-tiering a deployment is wiring, not code. Ring-1 and provider-free:
 * concrete providers are constructed by ring-2 modules and handed in via {@link #of(Map)}.
 */
public interface ModelRouter {

    /** The provider for a tier; never {@code null} (falls back to {@link ModelProvider#unavailable}). */
    ModelProvider providerFor(ModelTier tier);

    /**
     * The next-higher tier for escalation (SMALL→MEDIUM→LARGE→empty). Used by an escalation policy's
     * tier-bump rung; routing/availability of that tier is resolved separately via {@link #providerFor}.
     */
    default Optional<ModelTier> next(ModelTier tier) {
        ModelTier[] all = ModelTier.values();
        int i = tier.ordinal() + 1;
        return i < all.length ? Optional.of(all[i]) : Optional.empty();
    }

    /** True when at least one tier has an available provider. */
    default boolean anyAvailable() {
        for (ModelTier t : ModelTier.values()) {
            if (providerFor(t).available()) return true;
        }
        return false;
    }

    /** A router backed by an explicit tier→provider map; an unmapped tier resolves to unavailable. */
    static ModelRouter of(Map<ModelTier, ModelProvider> byTier) {
        Map<ModelTier, ModelProvider> copy = (byTier == null) ? Map.of() : Map.copyOf(byTier);
        return tier -> copy.getOrDefault(tier, ModelProvider.unavailable("no provider for tier " + tier));
    }

    /** All tiers served by the same provider — for tests injecting a single deterministic fake. */
    static ModelRouter of(ModelProvider single) {
        ModelProvider p = (single == null) ? ModelProvider.unavailable("null provider") : single;
        return tier -> p;
    }
}
