package com.gamma.agent.kernel.reason;

/**
 * A terminal or intermediate step the {@link EscalationPolicy} takes when confidence is below
 * threshold. The set is sealed and pluggable: UCC uses only {@link Abstain}; CVVE wires
 * {@link HumanHandoff} to a real review queue; {@link BumpModelTier} re-attempts at the next tier.
 */
public sealed interface EscalationRung
        permits EscalationRung.BumpModelTier, EscalationRung.HumanHandoff, EscalationRung.Abstain {

    /** Re-attempt at the next-higher model tier (if one exists). */
    record BumpModelTier() implements EscalationRung {}

    /** Park for human review on the named queue (terminal). */
    record HumanHandoff(String queue) implements EscalationRung {}

    /** Give up safely — return UNAVAILABLE rather than a low-confidence answer (terminal). */
    record Abstain() implements EscalationRung {}
}
