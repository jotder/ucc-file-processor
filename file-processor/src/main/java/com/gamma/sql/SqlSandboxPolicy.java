package com.gamma.sql;

/**
 * Resource caps for a {@link SqlSandbox} — the limits a locked-down validation connection runs under.
 * Introduced at M6 (v3.6.0) for the {@code kpi-to-sql} SQL oracle (architecture gap G4: unsandboxed
 * SQL). Deliberately small: this connection only ever {@code EXPLAIN}s and {@code LIMIT}-bounds an
 * agent-generated read-only query, never a production workload, so it should not be allowed to
 * oversubscribe the box even if a query plans expensively.
 *
 * <p>Defaults are a safety backstop, not a tuning knob. All fields are normalised + never-invalid via
 * the compact constructor so callers can read them without guards.
 *
 * @param memoryLimit          DuckDB {@code memory_limit} string (e.g. {@code "1GB"}); never blank
 * @param maxThreads           DuckDB {@code threads} cap for the sandbox connection ({@code >= 1})
 * @param queryTimeoutSeconds  JDBC statement timeout applied to each sandbox query ({@code >= 1})
 * @since 3.6.0
 */
public record SqlSandboxPolicy(String memoryLimit, int maxThreads, int queryTimeoutSeconds) {

    private static final String DEFAULT_MEMORY = "1GB";
    private static final int DEFAULT_THREADS = 2;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    public SqlSandboxPolicy {
        memoryLimit = (memoryLimit == null || memoryLimit.isBlank()) ? DEFAULT_MEMORY : memoryLimit.trim();
        if (maxThreads <= 0) maxThreads = DEFAULT_THREADS;
        if (queryTimeoutSeconds <= 0) queryTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    }

    /**
     * The production default: caps from {@code -Dassist.sql.memory_limit} / {@code -Dassist.sql.threads}
     * / {@code -Dassist.sql.timeout_seconds}, falling back to conservative built-ins.
     */
    public static SqlSandboxPolicy defaultPolicy() {
        return new SqlSandboxPolicy(
                System.getProperty("assist.sql.memory_limit", DEFAULT_MEMORY),
                intProp("assist.sql.threads", DEFAULT_THREADS),
                intProp("assist.sql.timeout_seconds", DEFAULT_TIMEOUT_SECONDS));
    }

    /** A policy with explicit caps (the skill's workspace, or a test). */
    public static SqlSandboxPolicy withCaps(String memoryLimit, int maxThreads, int queryTimeoutSeconds) {
        return new SqlSandboxPolicy(memoryLimit, maxThreads, queryTimeoutSeconds);
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
