package com.gamma.agent.kernel.reason;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.Capability;
import com.gamma.agent.kernel.agent.CapabilitySpec;
import com.gamma.agent.kernel.model.ModelTier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a capability with confidence-driven escalation. The flow: attempt at the effective tier →
 * estimate confidence → if the result is OK and confidence ≥ the capability's threshold, return it
 * (with the estimated confidence). Otherwise walk the configured {@link EscalationRung}s in order —
 * {@link EscalationRung.BumpModelTier} re-attempts at the next tier, {@link EscalationRung.HumanHandoff}
 * parks for review, {@link EscalationRung.Abstain} returns UNAVAILABLE. If the rungs are exhausted
 * without acceptance, the policy abstains.
 *
 * <p>This is a K1 <em>ingredient</em>, not the assembled orchestrator (deferred to R1).
 */
public final class EscalationPolicy {

    private final List<EscalationRung> rungs;

    public EscalationPolicy(List<EscalationRung> rungs) {
        this.rungs = (rungs == null) ? List.of() : List.copyOf(rungs);
    }

    public AgentResult run(Capability capability, AgentRequest request, AgentContext baseCtx,
                           ConfidenceEstimator estimator) {
        CapabilitySpec spec = capability.spec();
        double threshold = spec.confidenceThreshold();

        ModelTier tier = baseCtx.effectiveTier(spec.defaultTier());
        AgentContext ctx = baseCtx.withEffectiveTier(tier);
        AgentResult result = capability.run(request, ctx);
        double confidence = estimator.estimate(request, result, ctx);
        if (accepts(result, confidence, threshold)) return result.withConfidence(confidence);

        for (EscalationRung rung : rungs) {
            switch (rung) {
                case EscalationRung.BumpModelTier ignored -> {
                    Optional<ModelTier> next = ctx.models().next(tier);
                    if (next.isEmpty()) continue; // already at the top tier; try the next rung
                    tier = next.get();
                    ctx = baseCtx.withEffectiveTier(tier);
                    result = capability.run(request, ctx);
                    confidence = estimator.estimate(request, result, ctx);
                    if (accepts(result, confidence, threshold)) return result.withConfidence(confidence);
                }
                case EscalationRung.HumanHandoff handoff -> {
                    // 1.1 (ADR-0015): make the parked case self-describing. Carry the candidate attempt's
                    // payload (answer/evidence/links/rationale/data — e.g. the field errors a HITL consumer
                    // wants to show the reviewer) into the handoff result, plus the escalation routing keys.
                    // 1.0 only carried {escalation, queue}, forcing consumers to recompute the candidate.
                    // Additive: the two routing keys are still present, so existing readers are unaffected.
                    Map<String, Object> data = new LinkedHashMap<>(result.data());
                    data.put("escalation", "human-handoff");
                    data.put("queue", handoff.queue());
                    return new AgentResult(spec.id(), spec.version(), AgentResult.Status.UNAVAILABLE,
                            result.answer(), result.evidence(), result.links(), result.rationale(),
                            confidence, false, tier, null, null, "handed off for human review", data);
                }
                case EscalationRung.Abstain ignored -> {
                    return abstain(spec, confidence, threshold);
                }
            }
        }
        return abstain(spec, confidence, threshold);
    }

    private static boolean accepts(AgentResult result, double confidence, double threshold) {
        return result.status() == AgentResult.Status.OK && confidence >= threshold;
    }

    private static AgentResult abstain(CapabilitySpec spec, double confidence, double threshold) {
        return AgentResult.unavailable(spec.id(), "confidence " + fmt(confidence)
                + " below threshold " + fmt(threshold) + "; abstaining");
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }
}
