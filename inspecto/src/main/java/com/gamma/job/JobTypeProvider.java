package com.gamma.job;

import java.util.function.Function;

/**
 * One Job Type: a stable {@code id} (the {@code type:} string in a {@code *_job.toon}) and a factory
 * that builds the runnable {@link Job} for one authored config. This is the registry seam that
 * replaced the hard-coded {@link JobType} switch in {@code JobService.build()} (P0,
 * {@code docs/job-framework-design.md} §6.1).
 *
 * <p>In P0 the only providers are the four built-ins, registered internally. ServiceLoader-based
 * discovery, per-type descriptors, and hot-deployable Job Packs arrive with P2/P3 — at which point
 * this type widens to public API.
 */
interface JobTypeProvider {

    String id();

    Job create(JobConfig config);

    /** A provider from an id + a factory function (how the built-ins register). */
    static JobTypeProvider of(String id, Function<JobConfig, Job> factory) {
        return new JobTypeProvider() {
            @Override public String id() { return id; }
            @Override public Job create(JobConfig config) { return factory.apply(config); }
        };
    }
}
