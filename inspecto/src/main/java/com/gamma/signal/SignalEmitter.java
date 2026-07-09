package com.gamma.signal;

import java.util.Map;

/**
 * How a Job (or the framework) emits a {@link Signal} (job-framework §8.1, R6). The caller supplies
 * only the domain facts — {@code type}, {@code severity}, {@code payload}; the framework stamps the
 * signal id, timestamp, source ref, correlation id and space, then persists it to the one ledger.
 */
public interface SignalEmitter {

    void emit(String type, Severity severity, Map<String, Object> payload);
}
