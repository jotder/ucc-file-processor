package com.gamma.agent.kernel.observe;

import java.util.Set;

/** A capability invocation began. Carries the supplied context <em>keys</em> only — never values. */
public record AgentStarted(String capabilityId, long epochMillis, Set<String> contextKeys)
        implements AgentEvent {
    public AgentStarted {
        contextKeys = (contextKeys == null) ? Set.of() : Set.copyOf(contextKeys);
    }
}
