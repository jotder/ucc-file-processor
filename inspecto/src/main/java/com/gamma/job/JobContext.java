package com.gamma.job;

import java.util.Map;

/**
 * Everything one {@link Job} run may read. A narrow façade the framework populates per Run and
 * passes to {@link Job#run(JobContext)} — never the whole hosting service. Introduced in the P0
 * job-framework refactor ({@code docs/job-framework-design.md} §6.2); parameter resolution
 * ({@code params()}) and the {@code JobServices} data-plane façade arrive with P1.
 */
public interface JobContext {

    /** This run's id (matches the {@link JobRun#runId()} recorded for it). */
    String runId();

    /** The space this run executes under (per-space MDC routing). */
    String spaceId();

    /** How this run was started — {@code cron} / {@code event} / {@code manual} / {@code catch-up} + detail. */
    TriggerInfo trigger();

    /** The Job's own configuration (the {@code *_job.toon} params). Read-only. */
    Map<String, String> config();

    /** Structured, persisted per-run logging (R5). */
    RunLog log();
}
