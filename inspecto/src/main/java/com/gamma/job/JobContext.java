package com.gamma.job;

import com.gamma.signal.SignalEmitter;

import java.util.Map;

/**
 * Everything one {@link Job} run may read. A narrow façade the framework populates per Run and
 * passes to {@link Job#run(JobContext)} — never the whole hosting service. Introduced in the P0
 * job-framework refactor ({@code docs/job-framework-design.md} §6.2); the {@code JobServices}
 * data-plane façade arrives with the {@code sql.template} Job Type (P3).
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

    /**
     * The resolved runtime Parameters for this Run (R2/R3, §7): the Job Type's declared
     * {@link ParameterDecl}s resolved through config → deduce ({@code $}-context) → default. Empty when
     * the Job Type declares no parameters. Distinct from {@link #config()} (the raw authored {@code params:}).
     */
    Map<String, String> params();

    /** Structured, persisted per-run logging (R5). */
    RunLog log();

    /** Emit domain {@link com.gamma.signal.Signal}s onto the one ledger (R6); framework-stamped. */
    SignalEmitter signals();

    /** Record queryable Run Artifacts — produced Datasets / files (R7, §10). */
    ArtifactRecorder artifacts();
}
