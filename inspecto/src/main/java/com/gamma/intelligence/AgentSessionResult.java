package com.gamma.intelligence;

/**
 * The output of {@code POST /agent/sessions} — a live session's id and creation time.
 * {@code createdAt} is an ISO-8601 string (not {@code java.time.Instant}: the shared
 * {@code ApiContext.JSON} mapper carries no {@code jsr310} module, matching every other wire
 * record in the control plane, e.g. {@code SourceService.PipelineRun}).
 */
public record AgentSessionResult(String sessionId, String createdAt) {
}
