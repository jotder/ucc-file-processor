package com.gamma.agent.kernel.agent;

import com.gamma.agent.kernel.error.AgentError;

/**
 * One self-contained agent capability: it orchestrates tools and the model and narrates a validated
 * result. Generalizes UCC's {@code Skill}.
 *
 * <h3>The escalation ↔ tier contract (authors must follow this)</h3>
 * A capability must read the tier it generates at via {@code ctx.effectiveTier(spec().defaultTier())}
 * — never hard-coding {@code spec().defaultTier()} — so an {@code EscalationPolicy} can re-run the
 * attempt at a higher tier. This is the one contract capability authors must honour.
 */
public interface Capability {

    /** This capability's declarative spec. */
    CapabilitySpec spec();

    /** Produce a validated, ready-to-surface result for the request; throws {@link AgentError} on failure. */
    AgentResult run(AgentRequest request, AgentContext ctx) throws AgentError;
}
