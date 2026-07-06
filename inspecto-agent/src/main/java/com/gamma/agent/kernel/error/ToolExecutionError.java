package com.gamma.agent.kernel.error;

/** A deterministic tool failed while computing (bad args, downstream read failure, etc.). */
public final class ToolExecutionError extends AgentError {
    public ToolExecutionError(String message) { super(message); }
    public ToolExecutionError(String message, Throwable cause) { super(message, cause); }
    @Override public Category category() { return Category.TOOL_EXECUTION; }
}
