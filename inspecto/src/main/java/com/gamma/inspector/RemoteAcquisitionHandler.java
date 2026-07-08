package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.DuplicatePolicy;
import com.gamma.acquire.IntegrityChecker;
import com.gamma.acquire.LedgerEntry;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RateLimiter;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectors;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.etl.MarkerManager;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Remote-source acquisition (Phase E/F) for {@link SourceProcessor}: materialise the bytes of files
 * listed by a remote {@link SourceConnector} (SFTP/FTP/…) into the local staging tree so the rest of
 * the engine — dedup, markers, ledger, backup — treats them exactly like local files. Covers pre-fetch
 * dedup, rate-limited (optionally parallel) fetch + integrity verification, mtime preservation,
 * source-side post-actions, and dead-lettering of corrupt downloads. Observability is delegated to
 * {@link AcquisitionTelemetry}.
 *
 * <p>Package-private; called only from {@code SourceProcessor.collect}. The logger keeps
 * {@code SourceProcessor}'s category so log output is unchanged from when this code lived there.
 */
final class RemoteAcquisitionHandler {

    private static final Logger log = LoggerFactory.getLogger(SourceProcessor.class);

    private RemoteAcquisitionHandler() {}

    /**
     * Retrieve each ready remote file into the local staging tree (Phase E). For every file: emit FILE_DISCOVERED;
     * skip without spending bandwidth if it is already a known duplicate ({@link #isKnownDuplicate}); else
     * {@link #fetchAndVerify fetch + integrity-check} it to {@code <poll>/<relativePath>} and preserve the source
     * mtime so METADATA dedup stays stable across cycles. The returned {@link RemoteFile}s carry the local path,
     * so {@code dedupLocal} and the downstream batch path handle them with no special-casing. The
     * {@code inspecto_active_connections} gauge is held at 1 for the duration.
     */
    static List<RemoteFile> materializeRemote(PipelineConfig cfg, SourceConnector primary,
                                              List<RemoteFile> ready, RetryPolicy retry) {
        Path pollRoot = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        String etagAlgo = cfg.source().duplicate().algorithm();
        PipelineConfig.Fetch fetch = cfg.source().fetch();

        // Resolve + capability-validate the post-action once. on_unsupported=FAIL stops the cycle here, before any
        // bytes move; WARN_AND_CONTINUE/IGNORE degrade to RETAIN (null ⇒ apply nothing).
        PostAction postAction = resolvePostAction(cfg, primary);

        // Pre-fetch dedup: skip a known duplicate without spending bandwidth (no network). FILE_DISCOVERED is
        // emitted for every listed file; only the survivors are fetched.
        List<RemoteFile> toFetch = new ArrayList<>(ready.size());
        for (RemoteFile rf : ready) {
            AcquisitionTelemetry.emitFileEvent(cfg, EventType.FILE_DISCOVERED, "File discovered: " + rf.relativePath(), rf.relativePath());
            AcquisitionTelemetry.incDiscovered(cfg);
            if (isKnownDuplicate(cfg, rf, pollRoot)) { AcquisitionTelemetry.incDuplicatesSkipped(cfg); continue; }
            toFetch.add(rf);
        }
        if (toFetch.isEmpty()) return List.of();

        RateLimiter limiter = fetch.rateLimited() ? RateLimiter.perSecond(fetch.rateLimitBytesPerSec()) : null;
        int parallelism = Math.max(1, Math.min(fetch.parallelFetch(), toFetch.size()));
        List<RemoteFile> staged = java.util.Collections.synchronizedList(new ArrayList<>(toFetch.size()));

        // Sequential (the Phase-E default): the single already-open session does everything.
        if (parallelism == 1) {
            AcquisitionTelemetry.setActiveConnections(cfg, 1);
            try {
                for (RemoteFile rf : toFetch)
                    fetchOne(cfg, primary, rf, pollRoot, etagAlgo, retry, limiter, postAction, staged);
            } finally {
                AcquisitionTelemetry.setActiveConnections(cfg, 0);
            }
            return new ArrayList<>(staged);
        }

        // Parallel fetch (Phase F): the SPI's connectors each hold ONE non-thread-safe session, so concurrency is
        // a POOL of independent sessions, not shared reuse. The primary (already open, used for discovery) is one
        // pool member and is closed by the caller; the extras are owned and closed here. Taking from a bounded
        // pool naturally caps concurrency to the pool size — no separate semaphore needed.
        java.util.concurrent.BlockingQueue<SourceConnector> pool = new java.util.concurrent.LinkedBlockingQueue<>();
        List<SourceConnector> extras = new ArrayList<>();
        pool.add(primary);
        try {
            for (int i = 1; i < parallelism; i++) {
                SourceConnector c = SourceConnectors.forConfig(cfg);
                extras.add(c);
                pool.add(c);
            }
            AcquisitionTelemetry.setActiveConnections(cfg, parallelism);
            try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(toFetch.size());
                for (RemoteFile rf : toFetch) {
                    futures.add(ex.submit(() -> {
                        SourceConnector c = pool.take();   // blocks until a session frees up ⇒ bounds concurrency
                        try {
                            fetchOne(cfg, c, rf, pollRoot, etagAlgo, retry, limiter, postAction, staged);
                        } finally {
                            pool.put(c);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    try { f.get(); }
                    catch (Exception e) { log.error("Parallel fetch worker failed on {}", cfg.identity().pipelineName(), e); }
                }
            }
        } finally {
            AcquisitionTelemetry.setActiveConnections(cfg, 0);
            for (SourceConnector c : extras) {
                try { c.close(); } catch (Exception ignore) { /* best-effort: each extra session is owned here */ }
            }
        }
        return new ArrayList<>(staged);
    }

    /**
     * Retrieve one ready remote file: rate-limit (Phase F), fetch+integrity-verify (with retry), preserve the
     * source mtime, apply the source-side post-action, and add the local path to {@code staged}. A fetch/integrity
     * failure is already logged, metered and (for a corrupt download) quarantined inside {@link #fetchAndVerify};
     * the file is simply skipped. Called from one connector session that the caller has confined to this thread.
     */
    private static void fetchOne(PipelineConfig cfg, SourceConnector connector, RemoteFile rf, Path pollRoot,
                                 String etagAlgo, RetryPolicy retry, RateLimiter limiter, PostAction postAction,
                                 List<RemoteFile> staged) {
        if (limiter != null && rf.hasSize()) {
            try { limiter.acquire(rf.size()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        Path target = pollRoot.resolve(rf.relativePath()).normalize();
        Path fetched = fetchAndVerify(cfg, connector, rf, target, etagAlgo, retry);
        if (fetched == null) return;   // failure already handled (event + metric + quarantine/skip)

        if (rf.lastModified() != null) {
            try {
                Files.setLastModifiedTime(fetched, java.nio.file.attribute.FileTime.from(rf.lastModified()));
            } catch (java.io.IOException ignore) { /* best-effort: mtime preservation keeps METADATA dedup stable */ }
        }
        applyPostAction(cfg, connector, rf, postAction);
        staged.add(rf.withLocalPath(fetched));
    }

    /**
     * Resolve the configured {@code source.post_action} into a concrete {@link PostAction} for this cycle, or
     * {@code null} when nothing should be applied (RETAIN, an unknown kind, or an unsupported action under
     * {@code on_unsupported != FAIL}). The required {@link SourceConnector.Capability} is validated against the
     * connector; {@code on_unsupported=FAIL} throws to stop the cycle, the others degrade to RETAIN. A MOVE's
     * {@code archive_path} template is date-resolved against now.
     */
    private static PostAction resolvePostAction(PipelineConfig cfg, SourceConnector connector) {
        PipelineConfig.PostActionConfig pac = cfg.source().postAction();
        if (!pac.active()) return null;
        PostAction.Kind kind;
        try {
            kind = PostAction.Kind.valueOf(pac.onSuccess());
        } catch (IllegalArgumentException bad) {
            log.warn("Unknown source.post_action.on_success '{}' for {} — retaining source files",
                    pac.onSuccess(), cfg.identity().pipelineName());
            return null;
        }
        if (kind == PostAction.Kind.RETAIN) return null;

        SourceConnector.Capability needed = switch (kind) {
            case DELETE -> SourceConnector.Capability.DELETE;
            case MOVE   -> SourceConnector.Capability.MOVE;
            case RENAME -> SourceConnector.Capability.RENAME;
            case TAG    -> SourceConnector.Capability.TAG;
            case RETAIN -> null;
        };
        if (needed != null && !connector.capabilities().contains(needed)) {
            String msg = "source.post_action.on_success=" + kind + " but connector '" + connector.scheme()
                    + "' lacks capability " + needed;
            switch (pac.onUnsupported()) {
                case "FAIL"   -> throw new IllegalStateException(msg + " (on_unsupported=FAIL)");
                case "IGNORE" -> { return null; }
                default       -> { log.warn("[CONFIG] {} — retaining source files (on_unsupported=WARN_AND_CONTINUE)", msg); return null; }
            }
        }
        String archive = PostAction.resolveTemplate(pac.archivePath(), java.time.ZonedDateTime.now());
        return new PostAction(kind, archive, pac.tags());
    }

    /**
     * Apply the source-side post-action (Phase F) for a successfully fetched + validated file via
     * {@code connector.post}. A runtime failure here does <em>not</em> discard the file — the bytes are already
     * safely staged locally — it is logged + metered and the file proceeds to ingest. Emits {@code FILE_ARCHIVED}
     * on success.
     */
    private static void applyPostAction(PipelineConfig cfg, SourceConnector connector, RemoteFile rf, PostAction action) {
        if (action == null) return;
        try {
            connector.post(rf, action);
            AcquisitionTelemetry.emitFileArchived(cfg, rf, action.kind().name());
        } catch (Exception e) {
            AcquisitionTelemetry.incPostActionsFailed(cfg);
            log.warn("Post-action {} failed for {} on {}: {} — file already staged, continuing",
                    action.kind(), rf.relativePath(), cfg.identity().pipelineName(), e.getMessage());
        }
    }

    /**
     * Count-only pending approximation for a remote source on the read-only {@code countPending} path — it must
     * never fetch over the network. Returns the would-be staging paths of the ready files that pre-fetch dedup
     * wouldn't immediately skip; the caller only reads {@code size()}.
     */
    static List<File> pendingRemoteApprox(PipelineConfig cfg, List<RemoteFile> ready) {
        Path pollRoot = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        List<File> out = new ArrayList<>(ready.size());
        for (RemoteFile rf : ready)
            if (!isKnownDuplicate(cfg, rf, pollRoot)) out.add(pollRoot.resolve(rf.relativePath()).toFile());
        return out;
    }

    /**
     * Would this remote file be skipped as a known duplicate <em>before</em> spending bandwidth to fetch it?
     * PATH dedup: a marker for its staging location already exists. METADATA dedup: the listing's size+mtime
     * match the ledger fingerprint. ETAG dedup (ACQ-7): the listing's etag/version matches the fingerprint
     * (falling back to size+mtime when the connector supplies neither). CHECKSUM can't decide without the
     * bytes, so it never pre-skips (the file is fetched and {@code ledgerFilter} decides on content). Dedup
     * off ⇒ never a duplicate.
     */
    private static boolean isKnownDuplicate(PipelineConfig cfg, RemoteFile rf, Path pollRoot) {
        if (!cfg.processing().duplicateCheckEnabled()) return false;
        PipelineConfig.Duplicate dup = cfg.source().duplicate();
        if (!dup.contentBased())
            return MarkerManager.isAlreadyProcessed(pollRoot.resolve(rf.relativePath()).toFile(), cfg);
        DuplicatePolicy.Mode mode = DuplicatePolicy.Mode.from(dup.mode());
        boolean hasMetadata = rf.hasSize() && rf.lastModified() != null;
        if (mode == DuplicatePolicy.Mode.METADATA && hasMetadata) {
            LedgerEntry prior = AcquisitionLedgers.shared().find(cfg.source().id(), rf.relativePath()).orElse(null);
            return DuplicatePolicy.decide(DuplicatePolicy.Mode.METADATA, prior, rf.size(),
                    rf.lastModified().toEpochMilli(), null) == DuplicatePolicy.Decision.DUPLICATE;
        }
        if (mode == DuplicatePolicy.Mode.ETAG && (rf.etag() != null || rf.version() != null || hasMetadata)) {
            LedgerEntry prior = AcquisitionLedgers.shared().find(cfg.source().id(), rf.relativePath()).orElse(null);
            long mtime = rf.lastModified() != null ? rf.lastModified().toEpochMilli() : Long.MIN_VALUE;
            return DuplicatePolicy.decide(DuplicatePolicy.Mode.ETAG, prior, rf.size(), mtime,
                    null, rf.etag(), rf.version()) == DuplicatePolicy.Decision.DUPLICATE;
        }
        return false;
    }

    /**
     * Fetch one remote file to {@code target} (materialise once, in its final/staging home — the I/O-minimisation
     * rule), record transfer metrics + a FILE_FETCHED event, then verify integrity (size vs. listing, checksum vs.
     * etag). On any failure the bytes are discarded, a FILE_FETCH_FAILED event is emitted, and {@code null} is
     * returned so the file is skipped this cycle rather than processed corrupt. {@code AcquisitionException}
     * extends {@code IOException}, so the single catch covers protocol and local-IO faults alike.
     */
    private static Path fetchAndVerify(PipelineConfig cfg, SourceConnector connector, RemoteFile rf,
                                       Path target, String etagAlgo, RetryPolicy retry) {
        long t0 = System.nanoTime();
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            // Retry/backoff (Phase F): a transient fetch fault is retried before the file is given up on.
            Path got = retry.execute(() -> connector.fetchTo(rf, target));
            long bytes;
            try { bytes = Files.size(got); } catch (java.io.IOException e) { bytes = rf.hasSize() ? rf.size() : 0L; }
            AcquisitionTelemetry.recordFetch(cfg, bytes, (System.nanoTime() - t0) / 1_000_000_000.0);
            AcquisitionTelemetry.emitFileFetched(cfg, rf, bytes);

            IntegrityChecker.Result r = IntegrityChecker.verify(rf, got, etagAlgo);
            if (!r.ok()) {
                AcquisitionTelemetry.incDownloadsFailed(cfg);
                AcquisitionTelemetry.emitFileEvent(cfg, EventType.FILE_FETCH_FAILED,
                        "Integrity check failed: " + rf.relativePath() + " (" + r.detail() + ")", rf.relativePath());
                log.warn("Integrity check failed for {} on {}: {} — quarantining (dead-letter)",
                        rf.relativePath(), cfg.identity().pipelineName(), r.detail());
                quarantineCorrupt(cfg, got, rf);   // dead-letter (Phase F): preserve the corrupt bytes for inspection
                return null;
            }
            AcquisitionTelemetry.incDownloaded(cfg);
            AcquisitionTelemetry.emitFileEvent(cfg, EventType.FILE_VALIDATED, "File validated: " + rf.relativePath(), rf.relativePath());
            return got;
        } catch (Exception e) {
            // AcquisitionException (an IOException) from fetchTo, or a wrapped retry failure: skip this cycle.
            AcquisitionTelemetry.incDownloadsFailed(cfg);
            AcquisitionTelemetry.emitFileEvent(cfg, EventType.FILE_FETCH_FAILED,
                    "Fetch failed: " + rf.relativePath() + " (" + e.getMessage() + ")", rf.relativePath());
            log.warn("Failed to fetch {} on {} after retries: {} — skipping this cycle",
                    rf.relativePath(), cfg.identity().pipelineName(), e.getMessage());
            return null;
        }
    }

    /**
     * Dead-letter a corrupt download (Phase F): move the integrity-failed staged file into the quarantine tree
     * under the {@code corrupt_download} reason so the bytes survive for inspection, rather than silently
     * deleting them. If quarantine itself fails (e.g. no quarantine dir configured), fall back to deleting the
     * bad file so it is never ingested.
     */
    private static void quarantineCorrupt(PipelineConfig cfg, Path got, RemoteFile rf) {
        try {
            com.gamma.etl.QuarantineManager.quarantine(
                    got.toFile(), com.gamma.etl.QuarantineManager.REASON_CORRUPT_DOWNLOAD, false, cfg);
        } catch (java.io.IOException q) {
            log.warn("Could not quarantine corrupt download {} ({}) — deleting", rf.relativePath(), q.getMessage());
            try { Files.deleteIfExists(got); } catch (java.io.IOException ignore) { /* best-effort */ }
        }
    }
}
