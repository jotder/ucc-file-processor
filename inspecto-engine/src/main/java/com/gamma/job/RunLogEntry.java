package com.gamma.job;

import java.util.Map;

/**
 * One persisted {@link RunLog} entry: the owning run, a monotonic sequence, the timestamp (ISO-8601),
 * a level, the message, and a flattened key/value map. Serialized one-per-line as JSONL under
 * {@code <auditDir>/runlog/<runId>.jsonl}.
 */
public record RunLogEntry(String runId, int seq, String at, String level,
                          String message, Map<String, Object> kv) {}
