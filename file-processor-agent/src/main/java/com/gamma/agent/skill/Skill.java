package com.gamma.agent.skill;

import com.gamma.agentkernel.model.ModelTier;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;

/**
 * One self-contained assist capability (v3.3.0). A skill declares its {@link #id()} (the intent),
 * the {@link #tier()} of model it needs, and runs a single generate→(validate)→return cycle in
 * {@link #run}. Adding a skill is a registry entry + tests — no platform change.
 *
 * <p>Skills must be defensive: a skill whose model is {@link com.gamma.agent.model.ModelProvider#available()
 * unavailable} returns {@link AssistResult#unavailable(String, String)} rather than throwing, and
 * never performs autonomous writes.
 */
public interface Skill {

    /** The intent this skill answers, e.g. {@code "explain-entity"}. */
    String id();

    /** The model tier this skill needs. */
    ModelTier tier();

    /** Produce a validated, ready-to-surface result for the request. */
    AssistResult run(AssistRequest request, AssistContext ctx);
}
