package com.gamma.acquire;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gamma.event.EventLog;

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
 *
 * <p><b>Per-space.</b> {@link #shared()}/{@link #use(AcquisitionLedger)} resolve one ledger <em>per space</em>
 * ({@link EventLog#currentSpaceId()}) so each space keeps its own dedup history. With no space MDC set —
 * single-space and every existing test — everything resolves to the default-space ledger, identical to the
 * single singleton it replaces. (Per-space DB URLs from each space's {@code SpaceRoot} are wired in Stage 3,
 * when the MDC is actually set; until then every space lazily builds from the JVM-wide {@code -D}.)
 */
public final class AcquisitionLedgers {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionLedgers.class);
    private static final String DEFAULT_DB_URL = "jdbc:duckdb:inspecto-acquisition.db";

    private AcquisitionLedgers() {}

    /** One ledger per space ({@link EventLog#currentSpaceId()}); the default space's ledger replaces the old singleton. */
    private static final ConcurrentHashMap<String, AcquisitionLedger> LEDGERS = new ConcurrentHashMap<>();

    /** The ledger for the calling thread's space, lazily built from {@code -Dacquire.ledger.backend} on first use. */
    public static AcquisitionLedger shared() {
        return LEDGERS.computeIfAbsent(EventLog.currentSpaceId(), k -> build());
    }

    /** Install a specific ledger for the calling thread's space (tests / embedders). */
    public static void use(AcquisitionLedger ledger) {
        String space = EventLog.currentSpaceId();
        if (ledger == null) LEDGERS.remove(space);
        else LEDGERS.put(space, ledger);
    }

    /** Install {@code ledger} for an explicit {@code spaceId} (the per-space bootstrap, which has no MDC set). */
    public static void register(String spaceId, AcquisitionLedger ledger) {
        if (spaceId != null && ledger != null) LEDGERS.put(spaceId, ledger);
    }

    // ── checksum handoff (Phase C3) ────────────────────────────────────────────────
    // CHECKSUM dedup hashes each candidate once on the run path (in SourceProcessor.collect); this transient
    // cache hands that hash to BatchProcessor.commit so the post-commit ledger record reuses it instead of
    // re-reading the file. Keyed by absolute path; entries are removed on take. A batch that never commits
    // leaves a small orphan entry (harmless — recomputed on the next successful run). Stays process-wide
    // (NOT per-space): the absolute-path key never collides across spaces — each space polls its own dirs.poll.
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

    // ── DB-export row-level watermark handoff ──────────────────────────────────────
    // Mirrors the checksum handoff above: a DB-export connector computes the new max watermark while writing its
    // CSV in fetchTo and stashes it here, keyed by the staged file. BatchProcessor.commit takes it AFTER the batch
    // is durable and persists it to the ledger (recordDbWatermark) — so the watermark advances only on commit
    // (a crash mid-ingest re-exports the slice; at-least-once / resumable). A batch that never commits leaves a
    // small orphan entry (harmless — recomputed on the next successful run). The connection-profile id travels
    // with the value so the commit side stays source-type-agnostic (it just forwards the key+value).

    /** A stashed DB-export watermark: the connection-profile {@code key} it belongs to and its opaque {@code value}. */
    public record DbWatermark(String key, String value) {}

    private static final ConcurrentHashMap<String, DbWatermark> PENDING_DB_WATERMARKS = new ConcurrentHashMap<>();

    /** Stash the freshly-computed watermark for {@code dest} (the staged export file), to be persisted at commit. */
    public static void stashDbWatermark(Path dest, String sourceKey, String value) {
        if (sourceKey != null && value != null) PENDING_DB_WATERMARKS.put(key(dest), new DbWatermark(sourceKey, value));
    }

    /** Take (and remove) the stashed watermark for {@code dest}, or empty if none was stashed for that file. */
    public static java.util.Optional<DbWatermark> takeDbWatermark(Path dest) {
        return java.util.Optional.ofNullable(PENDING_DB_WATERMARKS.remove(key(dest)));
    }

    /** The lazy default for a space with no explicitly {@linkplain #register registered} ledger: the JVM-wide URL. */
    private static AcquisitionLedger build() {
        return build(System.getProperty("acquire.ledger.db.url", DEFAULT_DB_URL));
    }

    /**
     * Build a ledger at {@code url}. The backend toggle ({@code -Dacquire.ledger.backend}, memory default | db)
     * stays process-global — only the URL becomes per-space — mirroring {@link com.gamma.service.ServiceStores}.
     * A {@code db} URL that fails to open degrades to in-memory so acquisition is never blocked.
     */
    public static AcquisitionLedger build(String url) {
        String backend = System.getProperty("acquire.ledger.backend", "memory");
        if (!"db".equalsIgnoreCase(backend)) return new InMemoryAcquisitionLedger();
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
