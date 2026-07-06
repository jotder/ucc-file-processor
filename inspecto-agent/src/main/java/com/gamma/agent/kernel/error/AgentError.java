package com.gamma.agent.kernel.error;

/**
 * The sealed agent error taxonomy. A sealed <em>abstract class extending {@link RuntimeException}</em>
 * (not a bare interface) so it can be declared in {@code throws}, caught directly, and matched
 * exhaustively in a {@code switch}. Every failure inside the kernel is one of the permitted kinds;
 * the {@link Category} maps to a wire status at the app boundary.
 *
 * <p>Mapping applied by an app's orchestrator/registry:
 * {@code VALIDATION/MODEL/TOOL_EXECUTION/SYSTEM → unavailable}; an unknown capability → unsupported;
 * {@code AUTHORIZATION} is app-defined.
 */
public sealed abstract class AgentError extends RuntimeException
        permits ValidationError, AuthorizationError, ToolExecutionError, ModelError, SystemError {

    /** Coarse error class, used to map onto an app's wire status. */
    public enum Category { VALIDATION, AUTHORIZATION, TOOL_EXECUTION, MODEL, SYSTEM }

    protected AgentError(String message) { super(message); }

    protected AgentError(String message, Throwable cause) { super(message, cause); }

    /** The category of this error. */
    public abstract Category category();
}
