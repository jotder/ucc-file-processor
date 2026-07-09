package com.gamma.job;

/**
 * A single unit of schedulable work. Implementations wrap an engine call (ingest,
 * enrichment), a report computation, or a maintenance task behind one uniform contract,
 * so {@link JobService} can schedule, trigger, audit and report on all of them the same
 * way. Implementations should be safe to invoke repeatedly; {@code JobService} serialises
 * concurrent fires of the <em>same</em> job.
 */
public interface Job {

    /** Stable job name (from config); used for scheduling, audit and the API. */
    String name();

    /** Which kind of work this job performs. */
    JobType type();

    /**
     * Perform the work once and return its outcome. May throw; {@link JobService}
     * converts a thrown exception into a {@code FAILED} {@link JobResult} and records it.
     */
    JobResult run() throws Exception;

    /**
     * Perform the work once with a per-Run {@link JobContext} (structured logging, trigger info,
     * validated config). This is the entry point {@link JobService} invokes; the default bridges
     * to the legacy no-arg {@link #run()} so existing implementations keep working unchanged
     * (job-framework P0, {@code docs/job-framework-design.md} §6.3). New Job Types override this
     * and read from {@code ctx}; {@link #run()} becomes a default (deprecated) when the parameter
     * resolver lands in P1.
     */
    default JobResult run(JobContext ctx) throws Exception {
        return run();
    }
}
