package com.gamma.agent.kernel.observe;

import com.gamma.agent.kernel.model.ModelTier;

/** A model generation was issued at the given tier (with/without JSON formatting). No prompt text. */
public record ModelCalled(String capabilityId, long epochMillis, ModelTier tier, boolean jsonFormat)
        implements AgentEvent {}
