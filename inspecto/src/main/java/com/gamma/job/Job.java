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
}
