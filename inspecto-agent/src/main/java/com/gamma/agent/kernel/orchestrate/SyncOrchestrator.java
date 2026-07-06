package com.gamma.agent.kernel.orchestrate;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.Capability;
import com.gamma.agent.kernel.agent.CapabilityRegistry;
import com.gamma.agent.kernel.observe.AgentCompleted;
import com.gamma.agent.kernel.observe.HumanDecided;
import com.gamma.agent.kernel.reason.ConfidenceEstimator;
import com.gamma.agent.kernel.reason.EscalationPolicy;

import java.util.Objects;

/**
 * The assembled <em>synchronous</em> agent pipeline — the orchestrator K1 deferred to R1
 * (see {@link CapabilityRegistry}'s note and ADR-0009). It composes the ring-1 ingredients into the
 * one request → result flow a synchronous consumer needs:
 *
 * <ol>
 *   <li><b>Resolve</b> the capability bound to {@link AgentRequest#capabilityId()}; an unknown id
 *       yields {@link AgentResult#unsupported(String)} (still audited, so callers see the miss).</li>
 *   <li><b>Run with escalation</b> via {@link EscalationPolicy#run} — attempt at the effective tier,
 *       estimate confidence with the supplied {@link ConfidenceEstimator}, surface if it clears the
 *       capability's threshold, else walk the policy's rungs (e.g. abstain to UNAVAILABLE rather than
 *       ship a low-confidence guess).</li>
 *   <li><b>Audit</b> exactly one {@link AgentCompleted} to {@link AgentContext#audit()} — keys and
 *       summaries only, never data-plane values (ADR-0008).</li>
 * </ol>
 *
 * <p>This is precisely the pipeline UCC hand-rolled inside its {@code UccAssistAgent}; R1 lifts it here
 * so a second consumer composes the same ingredients rather than re-deriving them. It depends on
 * <em>no</em> ring-1 type beyond what already existed — the design test that the K1 seam was right.
 *
 * <p><b>Left to the caller</b> (these are app/transport concerns, not orchestration): mapping the
 * neutral {@link AgentResult} onto a wire/UI type, any human-readable logging (wrap the {@link
 * com.gamma.agent.kernel.observe.AuditSink} to add it), short-circuiting before the agent exists, and
 * catching unexpected {@link RuntimeException}s. Async and streaming variants are separate entry points
 * added when a second consumer (CVVE/CxO) shapes them; this class stays synchronous.
 */
public final class SyncOrchestrator {

    private final CapabilityRegistry registry;
    private final ConfidenceEstimator estimator;
    private final EscalationPolicy escalation;

    /**
     * @param registry   the id → capability table to resolve against
     * @param estimator  scores each attempt's confidence for the escalation gate
     * @param escalation the policy (and its rungs) applied when an attempt is below threshold
     */
    public SyncOrchestrator(CapabilityRegistry registry, ConfidenceEstimator estimator,
                            EscalationPolicy escalation) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.escalation = Objects.requireNonNull(escalation, "escalation");
    }

    /** Resolve → escalate → audit, returning the neutral result for the caller to map. */
    public AgentResult run(AgentRequest request, AgentContext ctx) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ctx, "ctx");
        long startNanos = System.nanoTime();
        Capability capability = registry.get(request.capabilityId()).orElse(null);
        AgentResult result = (capability == null)
                ? AgentResult.unsupported(request.capabilityId())
                : escalation.run(capability, request, ctx, estimator);
        ctx.audit().emit(Orchestrations.completed(request, result, startNanos));
        return result;
    }

    /**
     * Resume a case after a human reviewer's decision — the "return" half of the {@code HumanHandoff} loop
     * (1.1, ADR-0015). Re-dispatches the corrected {@code request} through the full pipeline (resolve →
     * escalate → audit one {@link AgentCompleted}), then emits one {@link HumanDecided} so the human's action
     * lands in the same audit stream as the machine decision. Returns the neutral re-run result for the
     * caller to map.
     *
     * <p>The caller builds the corrected request — applying the reviewer's field corrections and supplying
     * confidence that clears the threshold (a human verified it) so the re-run is accepted rather than parked
     * again. The kernel stays neutral about an app's correction semantics; it only blesses the round-trip
     * (re-run + closing audit) so consumers stop hand-rolling it. A terminal approve/reject that does
     * <em>not</em> re-run needs no orchestration: emit {@code HumanDecided.of(...)} on the audit sink directly.
     *
     * @param decision  a short, app-defined label for the decision (e.g. {@code "CORRECT"})
     * @param reviewer  who decided (an id/handle)
     * @param reference an opaque correlation id (e.g. the request or parked-case id)
     */
    public AgentResult resume(AgentRequest request, AgentContext ctx, String decision, String reviewer,
                              String reference) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ctx, "ctx");
        AgentResult result = run(request, ctx);
        ctx.audit().emit(HumanDecided.of(request.capabilityId(), decision, reviewer, reference));
        return result;
    }
}
