package com.gamma.agent.kernel.error;

/** The caller is not permitted to run this capability/tool, or lacks a required scope. */
public final class AuthorizationError extends AgentError {
    public AuthorizationError(String message) { super(message); }
    public AuthorizationError(String message, Throwable cause) { super(message, cause); }
    @Override public Category category() { return Category.AUTHORIZATION; }
}
