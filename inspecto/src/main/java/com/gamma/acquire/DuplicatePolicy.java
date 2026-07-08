package com.gamma.acquire;

/**
 * The duplicate-detection + change policy (Data Acquisition roadmap Phase C) — pure decision logic over a
 * candidate file's fingerprint and its prior {@link LedgerEntry}. How a re-seen path is judged ({@link Mode})
 * and what happens when its content changed ({@link OnChange}) are config-driven (the {@code source.duplicate}
 * block); this class turns those plus the ledger lookup into a {@link Decision}.
 *
 * <p>Pure and side-effect-free (the engine performs the resulting skip/process + ledger write), so it is unit
 * tested directly — the same shape as {@link RetrievalPlanner}.
 */
public final class DuplicatePolicy {

    private DuplicatePolicy() {}

    /** How a file already seen at the same path is judged. */
    public enum Mode {
        /** Path seen before ⇒ duplicate (today's {@code MarkerManager} sentinel semantics). */ PATH,
        /** Compare name+size+mtime (cheap; no file read). */                                    METADATA,
        /** Compare a content checksum (read at processing time). */                             CHECKSUM,
        /** Compare the connector-supplied etag/version from the listing (ACQ-7; free — no fetch, no file
         *  read). Falls back to version when the etag is absent, then to size+mtime when neither side has
         *  either — so a connector without {@code ETAG}/{@code VERSIONING} capability degrades to METADATA. */
        ETAG;

        public static Mode from(String s) {
            if (s == null) return PATH;
            return switch (s.trim().toUpperCase()) {
                case "METADATA" -> METADATA;
                case "CHECKSUM" -> CHECKSUM;
                case "ETAG" -> ETAG;
                default -> PATH;
            };
        }
    }

    /** What to do when a file at a known path has <em>changed</em> content. */
    public enum OnChange {
        /** Treat the change as a duplicate — skip it. */                              IGNORE,
        /** Process the new version (and update the ledger). */                        REPROCESS,
        /** Process it and emit {@code FILE_CHANGED} for alerting. */                  ALERT,
        /** Process it after the engine archives the previous version. */              ARCHIVE_OLD_VERSION;

        public static OnChange from(String s) {
            if (s == null) return REPROCESS;
            return switch (s.trim().toUpperCase()) {
                case "IGNORE" -> IGNORE;
                case "ALERT" -> ALERT;
                case "ARCHIVE_OLD_VERSION" -> ARCHIVE_OLD_VERSION;
                default -> REPROCESS;
            };
        }
    }

    /** The verdict for one candidate file. */
    public enum Decision { NEW, DUPLICATE, CHANGED }

    /**
     * Decide NEW / DUPLICATE / CHANGED for a candidate given its prior ledger entry (or {@code null} if none).
     *
     * @param checksum the candidate's content checksum, required only in {@link Mode#CHECKSUM}
     */
    public static Decision decide(Mode mode, LedgerEntry prior, long size, long lastModified, String checksum) {
        return decide(mode, prior, size, lastModified, checksum, null, null);
    }

    /**
     * Decide NEW / DUPLICATE / CHANGED for a candidate given its prior ledger entry (or {@code null} if none).
     *
     * @param checksum the candidate's content checksum, required only in {@link Mode#CHECKSUM}
     * @param etag     the candidate's listing etag, consulted only in {@link Mode#ETAG} (nullable)
     * @param version  the candidate's listing object version, consulted only in {@link Mode#ETAG} (nullable)
     */
    public static Decision decide(Mode mode, LedgerEntry prior, long size, long lastModified,
                                  String checksum, String etag, String version) {
        if (prior == null) return Decision.NEW;
        return switch (mode) {
            case PATH -> Decision.DUPLICATE;
            case METADATA -> (prior.size() == size && prior.lastModified() == lastModified)
                    ? Decision.DUPLICATE : Decision.CHANGED;
            case CHECKSUM -> (checksum != null && checksum.equals(prior.checksum()))
                    ? Decision.DUPLICATE : Decision.CHANGED;
            case ETAG -> {
                // Strongest dimension both sides carry wins: etag, then version, then the metadata fallback —
                // an etag that differs is CHANGED even when size+mtime happen to match.
                if (etag != null && prior.etag() != null)
                    yield etag.equals(prior.etag()) ? Decision.DUPLICATE : Decision.CHANGED;
                if (version != null && prior.version() != null)
                    yield version.equals(prior.version()) ? Decision.DUPLICATE : Decision.CHANGED;
                yield (prior.size() == size && prior.lastModified() == lastModified)
                        ? Decision.DUPLICATE : Decision.CHANGED;
            }
        };
    }

    /** Whether a {@link Decision#CHANGED} file should be (re)processed under {@code policy} (vs. skipped). */
    public static boolean reprocessOnChange(OnChange policy) {
        return policy != OnChange.IGNORE;   // REPROCESS / ALERT / ARCHIVE_OLD_VERSION all process the new version
    }

    /** Whether {@code policy} should emit a {@code FILE_CHANGED} event when a change is detected. */
    public static boolean alertsOnChange(OnChange policy) {
        return policy == OnChange.ALERT;
    }
}
