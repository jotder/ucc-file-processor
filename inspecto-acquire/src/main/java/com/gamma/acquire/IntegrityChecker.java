package com.gamma.acquire;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that a file fetched from a {@link CollectorConnector} arrived intact (Data Acquisition roadmap Phase E,
 * requirement §11). Two independent, cheap-to-skip checks, applied to the staged local copy:
 *
 * <ul>
 *   <li><b>Size</b> — the staged file's byte count equals the size the connector's listing reported
 *       ({@link RemoteFile#size()}). Skipped when the listing carried no size ({@link RemoteFile#SIZE_UNKNOWN}),
 *       as some FTP servers omit it.</li>
 *   <li><b>Checksum</b> — when the listing exposes a content hash ({@link RemoteFile#etag()}, e.g. an object
 *       store's MD5 etag) the staged file is hashed with the matching {@link Checksums} algorithm and compared.
 *       Skipped when no etag is available (plain SFTP/FTP expose none), so this never forces a wasted read.</li>
 * </ul>
 *
 * <p>Pure verification — no events, no metrics; the caller decides what to do with a {@link Result}. Reuses
 * {@link Checksums} (JDK-only) so it adds no dependency.
 */
public final class IntegrityChecker {

    private IntegrityChecker() {}

    /** Outcome of verifying a staged file. {@code ok} is the verdict; {@code detail} explains a failure. */
    public record Result(boolean ok, String detail) {
        public static final Result PASSED = new Result(true, null);
        public static Result failed(String detail) { return new Result(false, detail); }
    }

    /**
     * Verify {@code staged} against the metadata in {@code source}.
     *
     * @param source the discovered file (carries the expected size / etag from the listing)
     * @param staged the local copy that was just fetched
     * @param etagAlgorithm the checksum algorithm an etag is expressed in (e.g. {@code "MD5"}); a {@code null}/blank
     *                      value, or a {@code null} etag, skips the checksum comparison
     */
    public static Result verify(RemoteFile source, Path staged, String etagAlgorithm) {
        long actualSize;
        try {
            actualSize = Files.size(staged);
        } catch (IOException e) {
            return Result.failed("cannot stat staged file: " + e.getMessage());
        }

        if (source.hasSize() && source.size() != actualSize) {
            return Result.failed("size mismatch: listing=" + source.size() + " received=" + actualSize);
        }

        String etag = source.etag();
        if (etag != null && !etag.isBlank() && etagAlgorithm != null && !etagAlgorithm.isBlank()) {
            try {
                String actual = Checksums.of(staged, etagAlgorithm);
                if (!stripQuotes(etag).equalsIgnoreCase(actual)) {
                    return Result.failed("checksum mismatch: etag=" + etag + " computed=" + actual);
                }
            } catch (IOException e) {
                return Result.failed("checksum failed: " + e.getMessage());
            }
        }
        return Result.PASSED;
    }

    /** Object-store etags are often quoted ({@code "abc123"}); compare against the bare hex. */
    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1);
        return t;
    }
}
