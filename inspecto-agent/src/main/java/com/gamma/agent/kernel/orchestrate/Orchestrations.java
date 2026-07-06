package com.gamma.agent.kernel.orchestrate;

import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.observe.AgentCompleted;

/**
 * Shared internals for the orchestrator entry points (sync and streaming) — the one place the keys-only
 * {@link AgentCompleted} audit summary is built, so every entry point audits identically (ADR-0008/0009).
 */
final class Orchestrations {

    private Orchestrations() {
    }

    /**
     * Build the keys-only {@link AgentCompleted} summary from the request + result (ADR-0008): no record
     * contents, no evidence values, no prompts. {@code repairRounds} reads the conventional
     * {@code data["repaired"]} boolean a capability may set; token counts are not tracked here (they
     * arrive via {@code ModelCalled} events when wired).
     */
    static AgentCompleted completed(AgentRequest request, AgentResult result, long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
        boolean repaired = Boolean.TRUE.equals(result.data().get("repaired"));
        return new AgentCompleted(request.capabilityId(), System.currentTimeMillis(), result.status(),
                result.evidence().size(), durationMs, request.screenContext().keySet(), result.servedBy(),
                result.servedBy() != null, repaired ? 1 : 0, result.confidence(), 0, 0);
    }
}
