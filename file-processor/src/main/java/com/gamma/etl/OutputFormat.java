package com.gamma.etl;

/**
 * The output formats {@link PartitionWriter} can emit, modelled as a self-describing
 * (enum-as-strategy) type instead of scattered {@code "PARQUET".equals(...)} string
 * checks. Each constant owns the three format-specific facts the writer needs:
 * its file extension, the DuckDB {@code COPY ... (FORMAT <token>)} token, and whether
 * a {@code COMPRESSION} option applies.
 *
 * <p>Adding a new output format (e.g. {@code JSON}, {@code ORC}) becomes a single
 * closed-enum edit plus a {@link #resolve(String)} mapping, rather than touching the
 * writer's branching logic.
 *
 * <p><b>Behaviour parity.</b> {@link #resolve(String)} reproduces the historical rule
 * exactly — {@code PARQUET} only for the canonical {@code "PARQUET"} token (config
 * upper-cases {@code output.format} at load time), every other value falling back to
 * {@code CSV} — so the emitted COPY SQL is byte-identical to the pre-refactor writer.
 */
public enum OutputFormat {

    /** Hive-partitioned Parquet; honours {@code output.compression}. */
    PARQUET(".parquet", true),

    /** Hive-partitioned CSV; ignores compression. */
    CSV(".csv", false);

    private final String  extension;
    private final boolean supportsCompression;

    OutputFormat(String extension, boolean supportsCompression) {
        this.extension           = extension;
        this.supportsCompression = supportsCompression;
    }

    /** The {@code COPY ... (FORMAT <token>)} token — the canonical DuckDB format name. */
    public String copyToken() {
        return name();   // "PARQUET" / "CSV"
    }

    /** Final-file extension, including the leading dot ({@code ".parquet"} / {@code ".csv"}). */
    public String extension() {
        return extension;
    }

    /** Whether a non-blank {@code COMPRESSION} option should be appended to the COPY. */
    public boolean supportsCompression() {
        return supportsCompression;
    }

    /**
     * Resolve a (config-normalised, upper-cased) output-format token. Mirrors the
     * historical rule: {@code PARQUET} only for the exact {@code "PARQUET"} token;
     * every other value — including {@code "CSV"} — resolves to {@code CSV}.
     */
    public static OutputFormat resolve(String token) {
        return "PARQUET".equals(token) ? PARQUET : CSV;
    }
}
