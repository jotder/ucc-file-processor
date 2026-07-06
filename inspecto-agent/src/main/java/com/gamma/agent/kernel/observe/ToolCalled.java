package com.gamma.agent.kernel.observe;

/** A tool invocation began. Carries the tool id only — never its arguments. */
public record ToolCalled(String capabilityId, long epochMillis, String toolId) implements AgentEvent {}
