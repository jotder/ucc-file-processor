package com.gamma.agent.kernel.error;

/** Input or output failed deterministic validation (schema, oracle, grounding). */
public final class ValidationError extends AgentError {
    public ValidationError(String message) { super(message); }
    public ValidationError(String message, Throwable cause) { super(message, cause); }
    @Override public Category category() { return Category.VALIDATION; }
}
