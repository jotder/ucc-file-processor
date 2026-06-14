package com.gamma.acquire;

/**
 * One row of the {@link AcquisitionLedger} — the durable fingerprint of a file that has been processed
 * (Data Acquisition roadmap Phase C). Keyed by {@code (sourceId, relativePath)}; the remaining fields are the
 * fingerprint the {@link DuplicatePolicy} compares a freshly-discovered file against to decide NEW / DUPLICATE
 * / CHANGED.
 *
 * <p>{@code checksum} is populated only in {@link DuplicatePolicy.Mode#CHECKSUM} mode (it requires reading the
 * file, so it is computed at processing time, not at discovery); {@code size}/{@code lastModified} carry the
 * cheap metadata fingerprint. {@code processedAt} is epoch-millis; {@code status} is a short lifecycle tag
 * (normally {@link #PROCESSED}).
 */
public record LedgerEntry(String sourceId, String relativePath, String name, long size,
                          String checksum, long lastModified, long processedAt, String status) {

    /** The usual {@link #status} of a recorded entry: the file's batch committed. */
    public static final String PROCESSED = "PROCESSED";

    /** A fingerprint with no checksum (METADATA/PATH modes). */
    public static LedgerEntry metadata(String sourceId, String relativePath, String name,
                                       long size, long lastModified, long processedAt) {
        return new LedgerEntry(sourceId, relativePath, name, size, null, lastModified, processedAt, PROCESSED);
    }
}
