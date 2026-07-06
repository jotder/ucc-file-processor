package com.gamma.agent.kernel.observe;

import com.gamma.agent.kernel.error.AgentError;

/**
 * A capability invocation failed. Carries the error {@link AgentError.Category} and a short, stable
 * {@code reason} summary — never a raw message that could embed data-plane values (ADR-0008).
 */
public record AgentFailed(String capabilityId, long epochMillis, AgentError.Category category, String reason)
        implements AgentEvent {}
