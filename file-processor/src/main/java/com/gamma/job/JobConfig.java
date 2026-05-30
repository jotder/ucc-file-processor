package com.gamma.job;

import com.gamma.service.CronExpression;
import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for one config-driven {@link Job}, loaded from a {@code *_job.toon} file.
 * A job declares <em>what</em> kind of work it does ({@link JobType}), <em>when</em> it
 * runs (a cron expression and/or an upstream pipeline event), and the type-specific
 * parameters it needs.
 *
 * <pre>
 * job:
 *   name: nightly-events-ingest
 *   type: ingest                 # ingest | enrich | report | maintenance
 *   cron: "0 2 * * *"            # optional — calendar schedule (5 or 6 cron fields)
 *   on_pipeline: UPSTREAM        # optional — also run when this pipeline commits a batch
 *   enabled: true                # optional — default true
 *   config: config/events_pipeline.toon   # type-specific params follow…
 * </pre>
 *
 * <p>Recognised top-level keys are {@code name}, {@code type}, {@code cron},
 * {@code on_pipeline} and {@code enabled}; every other key in the {@code job} section is
 * captured verbatim into {@link #params()} for the job implementation to read (e.g.
 * {@code config}, {@code scope}, {@code task}, {@code dir}, {@code retention_days}).
 *
 * @param name       unique job name
 * @param type       the kind of work
 * @param cron       cron expression, or {@code null}/blank for no schedule
 * @param onPipeline upstream pipeline/job name whose batch-commit triggers this job, or {@code null}
 * @param enabled    whether the scheduler should arm this job
 * @param params     type-specific parameters (all values as strings)
 */
public record JobConfig(String name, JobType type, String cron, String onPipeline,
                        boolean enabled, Map<String, String> params) {

    public boolean hasCron()  { return cron != null && !cron.isBlank(); }
    public boolean hasEvent() { return onPipeline != null && !onPipeline.isBlank(); }

    /** A required param, or an {@link IllegalArgumentException} naming the job. */
    public String require(String key) {
        String v = params.get(key);
        if (v == null || v.isBlank())
            throw new IllegalArgumentException("Job '" + name + "' (" + type
                    + ") requires param '" + key + "'");
        return v;
    }

    /** An optional param with a fallback. */
    public String opt(String key, String fallback) {
        String v = params.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** Parse the cron field into a validated {@link CronExpression} (caller checks {@link #hasCron()}). */
    public CronExpression cronExpression() {
        return CronExpression.parse(cron);
    }

    // ── factory ────────────────────────────────────────────────────────────────

    public static JobConfig load(String configPath) throws IOException {
        Map<String, Object> raw = ToonHelper.load(configPath);
        Map<String, Object> job = ToonHelper.requireSection(raw, "job");

        String name = ToonHelper.require(job, "name", "job");
        JobType type = JobType.from(ToonHelper.opt(job, "type", null));
        String cron = ToonHelper.opt(job, "cron", null);
        String onPipeline = ToonHelper.opt(job, "on_pipeline", null);
        boolean enabled = !"false".equalsIgnoreCase(ToonHelper.opt(job, "enabled", "true"));

        // validate the cron eagerly so a bad expression fails at load, not at first fire
        if (cron != null && !cron.isBlank()) CronExpression.parse(cron);

        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : job.entrySet()) {
            switch (e.getKey()) {
                case "name", "type", "cron", "on_pipeline", "enabled" -> { /* known keys */ }
                default -> { if (e.getValue() != null) params.put(e.getKey(), e.getValue().toString()); }
            }
        }
        return new JobConfig(name, type, cron, onPipeline, enabled, params);
    }
}
