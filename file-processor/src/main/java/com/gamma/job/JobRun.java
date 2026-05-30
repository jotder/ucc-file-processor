package com.gamma.job;

/**
 * The audit record of one {@link Job} execution — persisted to {@code jobs_runs.csv} and
 * kept in a short in-memory history for the Control API's {@code /jobs/{name}/runs}.
 */
public record JobRun(String runId, String job, String type, String trigger,
                     String startTime, String endTime, String status,
                     long durationMs, String message) {}
