package com.gamma.agent.kernel.tool;

import java.time.Instant;

/**
 * A provenance-tagged value produced by a {@link Tool}: the {@code value}, its {@link CredibilityTier},
 * an optional app-specific {@code tierLabel} (the {@code 0.x} escape hatch — used when the enum is too
 * coarse; {@code null}/blank ⇒ use {@link #tier}), a {@code sourceRef} <em>locator</em> (a catalog node
 * id, report anchor, or doc reference — never the value itself; ADR-0008), a {@code confidence}, and
 * when it was {@code observedAt}.
 */
public record Evidence(Object value, CredibilityTier tier, String tierLabel, String sourceRef,
                       double confidence, Instant observedAt) {

    /** Full-trust evidence with a source locator and unknown observation time. */
    public static Evidence of(Object value, CredibilityTier tier, String sourceRef) {
        return new Evidence(value, tier, null, sourceRef, 1.0, null);
    }

    /** The effective tier label: the app-specific {@link #tierLabel} if set, else the enum name. */
    public String effectiveTierLabel() {
        return (tierLabel == null || tierLabel.isBlank()) ? tier.name() : tierLabel;
    }
}
