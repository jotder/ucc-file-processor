package com.gamma.agent.kernel.tool;

/**
 * The shared credibility <em>vocabulary</em> for a piece of {@link Evidence}. The constants name common
 * provenance levels; {@link Evidence#tierLabel()} is the String escape hatch for an app-specific tier that
 * doesn't fit by name.
 *
 * <p><b>Declaration order is NOT a trust ranking.</b> How tiers rank is an application concern, and two
 * consumers rank them differently — e.g. CxO ranks {@code USER_PROVIDED} above {@code INDICATIVE}, another
 * app need not — so ring-1 imposes no canonical order and no ring-1 code reads {@link Enum#ordinal()} on
 * this type. An application supplies its own rank/{@code Comparator} over the tiers it uses.
 *
 * <p>The R1 rule-of-three review confirmed the <em>vocabulary</em> generalizes by name across two consumers
 * while the <em>ordering</em> does not: so the enum stays a vocabulary and is <em>not</em> promoted to an
 * app-extensible interface. See ADR-0014 (which supersedes the "ordered"/interface-promotion facet of
 * ADR-0004).
 */
public enum CredibilityTier {
    /** Source of record; the authoritative system for this fact. */
    AUTHORITATIVE,
    /** Official but not the system of record (published figure, official doc). */
    OFFICIAL,
    /** Indicative/estimated from a credible source. */
    INDICATIVE,
    /** Derived/computed by a deterministic tool from other evidence. */
    DERIVED,
    /** Supplied by the user in the request. */
    USER_PROVIDED,
    /** An assumption or default with no stronger backing. */
    ASSUMPTION
}
