package com.gamma.job;

/**
 * Structured per-Run logging (R5, {@code docs/job-framework-design.md} §9). Entries are persisted
 * append-only under the jobs audit dir and served at {@code GET /jobs/{name}/runs/{runId}/log}. The
 * {@code kv} varargs are flattened to key/value pairs. Secrets never reach the log by construction
 * (a {@code ${ENV:KEY}} value is resolved server-side, outside any config a Run sees).
 */
public interface RunLog {

    void info(String message, Object... kv);

    void warn(String message, Object... kv);

    void error(String message, Throwable t, Object... kv);
}
