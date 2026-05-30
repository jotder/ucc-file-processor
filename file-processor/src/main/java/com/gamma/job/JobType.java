package com.gamma.job;

/**
 * The kinds of work a config-driven {@link Job} can perform. One uniform scheduler and
 * registry ({@link JobService}) runs all of them, so an operator defines "what runs when"
 * entirely in {@code .toon} config regardless of which engine does the work.
 */
public enum JobType {
    /** Run a Stage-1 ingest pipeline once (param {@code config} = pipeline {@code .toon}). */
    INGEST,
    /** Run a Stage-2 enrichment once (param {@code config} = enrichment {@code .toon}). */
    ENRICH,
    /** Compute a status / batch-audit report and log a summary (param {@code scope}). */
    REPORT,
    /** A built-in maintenance task (param {@code task}, e.g. {@code cleanup}). */
    MAINTENANCE;

    public static JobType from(String s) {
        if (s == null) throw new IllegalArgumentException("job.type is required");
        try {
            return JobType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown job.type '" + s
                    + "' (expected one of: ingest, enrich, report, maintenance)");
        }
    }
}
