package com.gamma.agent.kernel.error;

/** The model provider was unavailable or the generation call failed. */
public final class ModelError extends AgentError {
    public ModelError(String message) { super(message); }
    public ModelError(String message, Throwable cause) { super(message, cause); }
    @Override public Category category() { return Category.MODEL; }
}
