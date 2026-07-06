package com.gamma.agent.kernel.error;

/** An unexpected internal failure not covered by the other categories (e.g. a deadline breach). */
public final class SystemError extends AgentError {
    public SystemError(String message) { super(message); }
    public SystemError(String message, Throwable cause) { super(message, cause); }
    @Override public Category category() { return Category.SYSTEM; }
}
