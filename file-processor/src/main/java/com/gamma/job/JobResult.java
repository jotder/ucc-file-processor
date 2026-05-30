package com.gamma.job;

/**
 * The outcome of one {@link Job} execution: a terminal status, a human-readable
 * message, and the wall-clock duration. {@code SKIPPED} is used when a job fires while a
 * previous run of the same job is still in flight (non-overlap guard).
 */
public record JobResult(String status, String message, long durationMs) {

    public static JobResult ok(String message, long durationMs)     { return new JobResult("SUCCESS", message, durationMs); }
    public static JobResult failed(String message, long durationMs) { return new JobResult("FAILED",  message, durationMs); }
    public static JobResult skipped(String message)                 { return new JobResult("SKIPPED", message, 0L); }

    public boolean success() { return "SUCCESS".equals(status); }
}
