package com.gamma.acquire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide {@link AcquisitionLedger} accessor (Data Acquisition roadmap Phase C wiring) — the global-registry
 * idiom of {@link com.gamma.metrics.MetricRegistry#global()} / {@link StabilityGate#shared()}, so the static
 * poll/commit cycle reads and writes one shared ledger without threading it through every call.
 *
 * <p>The backend is chosen once from {@code -Dacquire.ledger.backend} (<b>memory</b> default | {@code db}); a DB
 * that fails to open degrades to in-memory so acquisition is never blocked. The in-memory default holds no OS
 * handle; a DB connection lives for the process (closed at JVM exit). {@link #use(AcquisitionLedger)} installs a
 * specific ledger — for tests and for an embedder that owns the lifecycle.
 */
public final class AcquisitionLedgers {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionLedgers.class);
    private static final String DEFAULT_DB_URL = "jdbc:duckdb:inspecto-acquisition.db";

    private AcquisitionLedgers() {}

    private static volatile AcquisitionLedger shared;

    /** The process-wide ledger, lazily built from {@code -Dacquire.ledger.backend} on first use. */
    public static AcquisitionLedger shared() {
        AcquisitionLedger l = shared;
        if (l == null) {
            synchronized (AcquisitionLedgers.class) {
                if (shared == null) shared = build();
                l = shared;
            }
        }
        return l;
    }

    /** Install a specific ledger (tests / embedders). */
    public static synchronized void use(AcquisitionLedger ledger) {
        shared = ledger;
    }

    // ── checksum handoff (Phase C3) ────────────────────────────────────────────────
    // CHECKSUM dedup hashes each candidate once on the run path (in SourceProcessor.collect); this transient
    // cache hands that hash to BatchProcessor.commit so the post-commit ledger record reuses it instead of
    // re-reading the file. Keyed by absolute path; entries are removed on take. A batch that never commits
    // leaves a small orphan entry (harmless — recomputed on the next successful run).
    private static final ConcurrentHashMap<String, String> PENDING_CHECKSUMS = new ConcurrentHashMap<>();

    /** Stash a freshly-computed checksum for {@code file}, to be consumed at commit. */
    public static void stashChecksum(Path file, String checksum) {
        if (checksum != null) PENDING_CHECKSUMS.put(key(file), checksum);
    }

    /** Take (and remove) a stashed checksum for {@code file}, or {@code null} if none was stashed. */
    public static String takeChecksum(Path file) {
        return PENDING_CHECKSUMS.remove(key(file));
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private static AcquisitionLedger build() {
        String backend = System.getProperty("acquire.ledger.backend", "memory");
        if (!"db".equalsIgnoreCase(backend)) return new InMemoryAcquisitionLedger();
        String url = System.getProperty("acquire.ledger.db.url", DEFAULT_DB_URL);
        try {
            AcquisitionLedger db = DbAcquisitionLedger.open(url,
                    System.getProperty("acquire.ledger.db.user"), System.getProperty("acquire.ledger.db.password"));
            log.info("Acquisition ledger backend: database ({})", url);
            return db;
        } catch (Exception e) {
            log.warn("Could not open acquisition ledger DB at {} — falling back to in-memory: {}", url, e.getMessage());
            return new InMemoryAcquisitionLedger();
        }
    }
}
