package com.gamma.agent.kernel.observe;

/**
 * A human reviewer closed a case that was parked via {@link com.gamma.agent.kernel.reason.EscalationRung.HumanHandoff}
 * — the "return" half of the human-in-the-loop loop, recorded in the same audit stream as the machine
 * decisions ({@link AgentCompleted}). Until 1.1 the kernel modelled the <em>handoff out</em> but never the
 * <em>return</em>, so a human approve/reject/correct landed only in the app's own ledger, never in the
 * kernel audit trail; this event closes that gap, giving one unified compliance trail.
 *
 * <p>Keys and summaries only, never data-plane values (ADR-0008): {@code decision} is a short label (the
 * kernel stays neutral about an app's vocabulary — e.g. {@code "APPROVE"}, {@code "REJECT"}, {@code "CORRECT"}),
 * {@code reviewer} is who decided (an id/handle), and {@code reference} is an opaque correlation id (e.g. the
 * request or parked-case id) tying this back to the original {@link AgentCompleted}. No corrected values, no
 * record contents.
 *
 * <p>For a correction that re-enters the pipeline, prefer {@code SyncOrchestrator.resume(...)}, which re-runs
 * the corrected request and emits this event. For a terminal approve/reject that does not re-run, emit it
 * directly: {@code ctx.audit().emit(HumanDecided.of(capabilityId, "APPROVE", reviewer, caseId))}.
 */
public record HumanDecided(String capabilityId, long epochMillis, String decision, String reviewer,
                           String reference) implements AgentEvent {

    /** Stamp the current time; the rest are identifiers/labels supplied by the app. */
    public static HumanDecided of(String capabilityId, String decision, String reviewer, String reference) {
        return new HumanDecided(capabilityId, System.currentTimeMillis(), decision, reviewer, reference);
    }
}
