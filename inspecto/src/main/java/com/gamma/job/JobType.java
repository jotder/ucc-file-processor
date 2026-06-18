package com.gamma.job;

/**
 * The kinds of work a config-driven {@link Job} can perform. One uniform scheduler and
 * registry ({@link JobService}) runs all of them, so an operator defines "what runs when"
 * entirely in {@code .toon} config regardless of which engine does the work.
 *
 * <p><b>There is deliberately no {@code INGEST} type (removed 2026-06-17, T23 / §3.8).</b>
 * Ingestion is the <em>pipeline's</em> sole responsibility, driven by the poll loop only;
 * a job is strictly a downstream custom function over data already at rest, never a
 * re-acquisition. Migrate any former {@code ingest} job to an {@code active: true} pipeline.
 */
public enum JobType {
    /** Run a Stage-2 enrichment once (param {@code config} = enrichment {@code .toon}). */
    ENRICH,
    /** Compute a status / batch-audit report and log a summary (param {@code scope}). */
    REPORT,
    /** A built-in maintenance task (param {@code task}, e.g. {@code cleanup}). */
    MAINTENANCE,
    /** Run an authored {@code *_flow.toon} flow over data at rest (param {@code flow} = flow id; T32). */
    FLOW;

    public static JobType from(String s) {
        if (s == null) throw new IllegalArgumentException("job.type is required");
        try {
            return JobType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown job.type '" + s
                    + "' (expected one of: enrich, report, maintenance, flow)");
        }
    }
}
