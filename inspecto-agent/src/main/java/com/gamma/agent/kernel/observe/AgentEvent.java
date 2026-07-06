package com.gamma.agent.kernel.observe;

/**
 * A sealed observability event. Variants carry <b>identifiers, counts, durations, tiers, token usage,
 * confidence, and provenance references — keys and summaries only</b>. They never carry data-plane
 * values: no record contents, no {@code Evidence.value}, no raw prompt/output text (ADR-0008).
 *
 * <p>{@link HumanDecided} (added in 1.1, ADR-0015) records a human reviewer's terminal decision on a parked
 * case. Adding it is non-breaking: every known sink dispatches with {@code instanceof} guards (never an
 * exhaustive switch), so an unrecognised variant is simply ignored.
 */
public sealed interface AgentEvent
        permits AgentStarted, AgentCompleted, AgentFailed, ModelCalled, ToolCalled, ToolCompleted,
                HumanDecided {

    /** The capability this event belongs to. */
    String capabilityId();

    /** Event time, epoch millis. */
    long epochMillis();
}
