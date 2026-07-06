package com.gamma.agent.kernel.tool;

import java.time.Duration;

/**
 * Declarative metadata for a {@link Tool}: a stable {@code id}, an integer {@code version}, a human
 * {@code description}, and a {@code maxExecutionTime} the runtime may enforce.
 */
public record ToolSpec(String id, int version, String description, Duration maxExecutionTime) {}
