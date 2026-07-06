package com.gamma.agent.kernel.agent;

import com.gamma.agent.kernel.model.ModelTier;

import java.time.Duration;
import java.util.Set;

/**
 * Declarative metadata for a {@link Capability}: stable {@code id} and {@code version}, a human
 * {@code description}, the {@code defaultTier} it runs at (escalation may raise it), a numeric
 * {@code confidenceThreshold} below which the runtime escalates/abstains, a {@code maxExecutionTime},
 * the {@code requiredContext} keys, and the {@code allowedTools} it may call.
 */
public record CapabilitySpec(String id, int version, String description, ModelTier defaultTier,
                             double confidenceThreshold, Duration maxExecutionTime,
                             Set<String> requiredContext, Set<String> allowedTools) {

    public CapabilitySpec {
        requiredContext = (requiredContext == null) ? Set.of() : Set.copyOf(requiredContext);
        allowedTools = (allowedTools == null) ? Set.of() : Set.copyOf(allowedTools);
    }
}
