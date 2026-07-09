package com.gamma.job;

/**
 * One persisted Run Artifact (R7, §10): the owning run + job, a monotonic {@code seq}, the logical
 * {@code name} and {@code kind} ({@code dataset} | {@code file}), the {@code ref} (dataset id or file
 * path), a {@link ResultSetMeta} for dataset kinds ({@code null} for files), {@code rows}/{@code bytes},
 * an optional {@code watermark} (record-time, ISO-8601) and {@code timeRange}, and the record time
 * {@code at}. Serialized one-per-line as JSONL under {@code <auditDir>/artifacts/<runId>.jsonl}; the
 * fields double as the {@code $upstream(...).<attr>} accessors ({@code ref}/{@code rows}/{@code watermark}/
 * {@code time_range}).
 */
public record RunArtifact(String runId, String job, int seq, String name, String kind,
                          String ref, ResultSetMeta resultSet, long rows, long bytes,
                          String watermark, String timeRange, String at) {}
