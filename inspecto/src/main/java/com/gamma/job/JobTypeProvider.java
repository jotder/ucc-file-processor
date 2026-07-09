package com.gamma.job;

import java.util.function.Function;

/**
 * One Job Type: its catalog {@link JobTypeDescriptor} (id, title, declared parameters, emitted signals,
 * artifacts) and a factory that builds the runnable {@link Job} for one authored config. This is the
 * registry seam that replaced the hard-coded {@link JobType} switch in {@code JobService.build()} (§6.1);
 * the four built-ins register through it today, and it becomes the {@code ServiceLoader} SPI that optional
 * modules and hot-deployable Job Packs implement (P2b/P2c).
 */
public interface JobTypeProvider {

    JobTypeDescriptor descriptor();

    Job create(JobConfig config);

    /** The registry key — the descriptor's id. */
    default String id() { return descriptor().id(); }

    /** A provider from a descriptor + a factory function (how the built-ins register). */
    static JobTypeProvider of(JobTypeDescriptor descriptor, Function<JobConfig, Job> factory) {
        return new JobTypeProvider() {
            @Override public JobTypeDescriptor descriptor() { return descriptor; }
            @Override public Job create(JobConfig config) { return factory.apply(config); }
        };
    }
}
