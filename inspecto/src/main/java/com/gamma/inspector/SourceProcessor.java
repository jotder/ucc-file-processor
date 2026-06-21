package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.Checksums;
import com.gamma.acquire.CircuitBreaker;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.DuplicatePolicy;
import com.gamma.acquire.GapDetector;
import com.gamma.acquire.GapTracker;
import com.gamma.acquire.IntegrityChecker;
import com.gamma.acquire.LedgerEntry;
import com.gamma.acquire.PostAction;
import com.gamma.acquire.RateLimiter;
import com.gamma.acquire.RemoteFile;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectors;
import com.gamma.acquire.StabilityGate;
import com.gamma.acquire.retry.RetryPolicy;
import com.gamma.api.PublicApi;
import com.gamma.etl.*;
import com.gamma.event.EventType;
import com.gamma.metrics.MetricRegistry;
import com.gamma.util.LogSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * ETL entry point. Reads a {@code .toon} pipeline config, scans the inbox for
 * matching CSV/CSV.GZ files, groups them into {@link Batch}es by schema (packed
 * to {@code processing.batch.max_files}/{@code max_bytes}), and processes each
 * batch in one pass via {@link BatchProcessor}.
 *
 * <p>A batch of one file is the legacy single-file case and keeps the
 * {@code <basename>_out.<ext>} output name.
 *
 * <p>Run via: {@code java -jar file-processor.jar <pipeline.toon>}
 */
@PublicApi(since = "1.0.0")
public class SourceProcessor {

    private static final Logger log = LoggerFactory.getLogger(SourceProcessor.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SourceProcessor <pipeline_config_path>");
            System.exit(1);
        }
        PipelineConfig cfg = PipelineConfig.load(args[0]);
        LogSetup.configure(cfg.dirs().logDir(), cfg.identity().pipelineName(), cfg.identity().runTimestamp());
        try {
            run(cfg);
        } catch (BatchProcessingException e) {
            // Partial-failure run: at least one batch threw. We've already logged
            // the per-batch stack traces; exit non-zero so the wrapper script
            // (run.sh / run.bat / cron job) detects the failure without log scraping.
            System.err.println("[FAIL] " + e.getMessage());
            System.exit(2);
        }
    }

    /** Run one poll cycle for {@code cfg}: plan batches and process them in parallel. */
    public static void run(PipelineConfig cfg) throws Exception {
        run(cfg, null);
    }

    /**
     * Run one poll cycle, emitting a {@link BatchEvent} to {@code onCommit} after each
     * SUCCESS batch commits (the service layer passes a bus sink here so downstream
     * stages can react). {@code onCommit} may be {@code null}.
     */
    public static void run(PipelineConfig cfg, java.util.function.Consumer<BatchEvent> onCommit)
            throws Exception {
        Path root           = Paths.get(cfg.dirs().poll()).toAbsolutePath();
        if (!Files.exists(root)) Files.createDirectories(root);

        MarkerManager.cleanupStaleMarkers(cfg);

        // The set of inbox files this cycle will ingest (matching, ready/stable, not already-processed).
        // The real run path emits readiness signals (FILE_STABLE + the waiting-stability gauge); the
        // read-only countPending scan does not.
        List<File> candidates = collect(cfg, true);

        if (candidates.isEmpty()) {
            log.info("No new files to process in {}", root);
            return;
        }

        // ── plan batches ─────────────────────────────────────────────────────────
        BatchPlanner.SchemaResolver resolver = (cfg.schemas().selector() != null)
                ? cfg.schemas().selector()::select
                : f -> new SchemaSelector.Selection(cfg.schemas().single(), null);

        List<Batch> batches = BatchPlanner.plan(
                candidates, resolver, cfg.processing().batchMaxFiles(), cfg.processing().batchMaxBytes(), cfg.identity().runTimestamp());
        log.info("Planned {} batch(es) from {} file(s) using {} thread(s)...",
                batches.size(), candidates.size(), cfg.processing().threads());

        // ── process batches in parallel ────────────────────────────────────────
        BatchAuditWriter audit = new BatchAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath(),
                cfg.dirs().commitLogPath());
        if (onCommit != null) audit.setCommitListener(onCommit);

        // Virtual threads + a Semaphore: a batch blocked on file I/O or DuckDB parks
        // its carrier cheaply instead of pinning a platform thread, but the semaphore
        // bounds how many batches do heavy work at once to cfg.processing().threads(). That gives us
        // the preferred model — virtual-thread concurrency with a controllable cap that
        // protects against I/O pressure and CPU oversubscription. Every batch is
        // submitted up front; all but `permits` of them simply park on acquire().
        int maxConcurrent  = Math.max(1, cfg.processing().threads());
        Semaphore permits  = new Semaphore(maxConcurrent);
        int failedBatches  = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Batch b : batches) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        BatchProcessor.process(b, cfg, audit);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            // f.get() blocks until each batch completes; track failures so main()
            // can surface a non-zero exit code on partial-failure runs.
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    failedBatches++;
                    log.error("Batch processing failed", e);
                }
            }
        }   // try-with-resources close() shuts the executor down and awaits all virtual threads
        if (failedBatches > 0) {
            throw new BatchProcessingException(failedBatches, batches.size());
        }
    }

    /**
     * The inbox files a poll cycle would ingest right now: regular files under the poll root that
     * match {@code processing.file_pattern}, are not under the errors/quarantine trees, and (when
     * duplicate-checking is on) have no {@code .processed} marker. This is exactly the candidate set
     * {@link #run(PipelineConfig, java.util.function.Consumer)} collects, factored out as a
     * <em>read-only</em> scan (no marker cleanup, no directory creation) so observability can report
     * "pending" work without side effects. Returns empty when the poll root does not exist yet.
     */
    public static List<File> collectCandidates(PipelineConfig cfg) throws java.io.IOException {
        return collect(cfg, false);   // read-only scan: no FILE_STABLE events, no gauge writes
    }

    /**
     * Discover → readiness-gate → duplicate-filter. Discovery is delegated to the configured source connector
     * (the built-in LOCAL connector reproduces the legacy poll-dir tree-walk exactly). When a
     * {@code source.stability} block is configured, {@link StabilityGate} holds back any file that is still
     * arriving (size/mtime not yet quiescent, or a {@code ready_marker} absent) so a half-written file is never
     * ingested; absent that block, every discovered file is a candidate immediately — Phase-A behaviour.
     * Duplicate-marker filtering then stays an engine concern applied on top, split out from the listing so a
     * large inbox can run the per-file marker stat in parallel (order need not be preserved).
     *
     * @param emitSignals {@code true} only on the real {@link #run} path — it then emits a
     *                    {@link EventType#FILE_STABLE} event per file the gate just released and refreshes the
     *                    {@code inspecto_files_waiting_stability} gauge; {@code countPending} passes
     *                    {@code false} so a dashboard poll stays side-effect-free.
     */
    private static List<File> collect(PipelineConfig cfg, boolean emitSignals) throws java.io.IOException {
        PipelineConfig.Source src = cfg.source();
        PipelineConfig.Stability st = src.stability();

        // When stability gating is on, fold the temp/in-flight excludes (*.tmp/*.partial/…) into discovery so
        // a file mid-write is never even listed; absent a stability block this stays empty ⇒ discovery unchanged.
        List<String> excludes = src.excludes();
        if (st.enabled() && st.excludeTempFiles()) {
            excludes = new ArrayList<>(excludes);
            excludes.addAll(st.tempPatterns());
        }
        DiscoveryContext ctx = new DiscoveryContext(src.includes(), excludes, src.recursiveDepth());
        RetryPolicy retry = RetryPolicy.from(src.retry());

        // The connector stays open through materialisation: a remote connector holds a session for the lifetime
        // of the cycle, and fetchTo() needs it. (The local connector holds nothing — close is a no-op.)
        try (SourceConnector connector = SourceConnectors.forConfig(cfg)) {
            boolean remote = !"local".equalsIgnoreCase(connector.scheme());

            // Circuit breaker (Phase F): if this source's breaker is OPEN (repeated connectivity failures), skip
            // the whole cycle rather than hammering a dead endpoint — it half-opens for one trial after cooldown.
            PipelineConfig.CircuitBreaker cb = src.circuitBreaker();
            if (remote && cb.enabled() && !CircuitBreaker.shared().allow(src.id(), cb.cooldownMillis())) {
                if (emitSignals)
                    log.warn("Source {} circuit OPEN — skipping this cycle (cooldown {}ms)",
                            cfg.identity().pipelineName(), cb.cooldownMillis());
                return List.of();
            }

            // Discover, with retry/backoff (Phase F) for transient remote faults. Connectivity success/failure
            // feeds the breaker so a flapping endpoint trips it; the local connector never throws here.
            List<RemoteFile> discovered;
            try {
                discovered = retry.execute(() -> connector.discover(ctx));
                if (remote && cb.enabled()) CircuitBreaker.shared().recordSuccess(src.id());
            } catch (Exception e) {
                if (remote && cb.enabled()
                        && CircuitBreaker.shared().recordFailure(src.id(), cb.failureThreshold()) && emitSignals)
                    AcquisitionTelemetry.emitCircuitOpen(cfg, e.getMessage());
                if (e instanceof java.io.IOException io) throw io;
                if (e instanceof RuntimeException re) throw re;
                throw new java.io.IOException("Discovery failed for " + cfg.identity().pipelineName(), e);
            }

            StabilityGate.StabilityResult gated = (st.enabled() && !discovered.isEmpty())
                    ? StabilityGate.shared().filter(src.id(), discovered, connector, st.windowMillis(), st.sizeChecks())
                    : null;
            List<RemoteFile> ready = (gated != null) ? gated.ready() : discovered;

            if (emitSignals && st.enabled()) {
                AcquisitionTelemetry.setWaitingGauge(cfg, gated != null ? gated.waiting().size() : 0);
                if (gated != null) for (RemoteFile f : gated.newlyStable()) AcquisitionTelemetry.emitFileStable(cfg, f);
            }

            // Gap detection (Phase D): over the full discovery listing (not the dedup-filtered candidates) so a
            // hole in the expected series is reported even when nothing new is ingestable this cycle. Run path only.
            if (emitSignals && cfg.source().gapDetection().active() && !discovered.isEmpty())
                detectGaps(cfg, discovered);

            // Incremental discovery (Phase C4): when source.incremental.watermark is set, drop candidates modified
            // strictly before the source's high-watermark (the max last_modified the ledger has recorded). Applied
            // BEFORE the remote/local split so a remote source spends no fetch bandwidth on old objects; gap
            // detection above already saw the full listing, so a hole is still reported.
            if (cfg.source().incremental().enabled() && !ready.isEmpty())
                ready = watermarkFilter(cfg, ready, emitSignals);

            if (ready.isEmpty()) return List.of();

            // Remote sources (SFTP/FTP/…) — Phase E: materialise the bytes into the local staging tree (the poll
            // root, mirrored at each file's relativePath, with the source mtime preserved) so the rest of the
            // engine — dedup, markers, ledger, backup — treats them exactly like local files with no further
            // change. Discovery for a remote source uses the connector (not a poll walk), so staged files are
            // never re-listed. A read-only pending scan NEVER fetches — it returns a count-only approximation.
            if (remote) {
                if (!emitSignals) return pendingRemoteApprox(cfg, ready);
                ready = materializeRemote(cfg, connector, ready, retry);
                if (ready.isEmpty()) return List.of();
            }

            return dedupLocal(cfg, ready, emitSignals);
        }
    }

    /**
     * Engine-side duplicate filtering applied on top of discovery — unchanged from the original local flow, now
     * also reached by staged remote files (which look identical: an on-disk file under the poll root). Dedup off
     * ⇒ every file; content-based ⇒ the fingerprint {@link #ledgerFilter}; otherwise the PATH marker-sentinel
     * filter (parallelised for a large inbox).
     */
    private static List<File> dedupLocal(PipelineConfig cfg, List<RemoteFile> ready, boolean emitSignals)
            throws java.io.IOException {
        if (!cfg.processing().duplicateCheckEnabled()) return toFiles(ready);   // dedup off
        if (cfg.source().duplicate().contentBased())
            return ledgerFilter(cfg, ready, emitSignals);

        List<File> matched = toFiles(ready);
        if (matched.size() > 1 && cfg.processing().threads() > 1) {
            return matched.parallelStream()
                    .filter(f -> !MarkerManager.isAlreadyProcessed(f, cfg))
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        List<File> candidates = new ArrayList<>(matched.size());
        for (File f : matched)
            if (!MarkerManager.isAlreadyProcessed(f, cfg)) candidates.add(f);
        return candidates;
    }

    /**
     * Retrieve each ready remote file into the local staging tree (Phase E). For every file: emit FILE_DISCOVERED;
     * skip without spending bandwidth if it is already a known duplicate ({@link #isKnownDuplicate}); else
     * {@link #fetchAndVerify fetch + integrity-check} it to {@code <poll>/<relativePath>} and preserve the source
     * mtime so METADATA dedup stays stable across cycles. The returned {@link RemoteFile}s carry the local path,
     * so {@link #dedupLocal} and the downstream batch path handle them with no special-casing. The
     * {@code inspecto_active_connections} gauge is held at 1 for the duration.
     */
    private static List<RemoteFile> materializeRemote(PipelineConfig cfg, SourceConnector primary,
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
    private static List<File> pendingRemoteApprox(PipelineConfig cfg, List<RemoteFile> ready) {
        Path pollRoot = Paths.get(cfg.dirs().poll()).toAbsolutePath().normalize();
        List<File> out = new ArrayList<>(ready.size());
        for (RemoteFile rf : ready)
            if (!isKnownDuplicate(cfg, rf, pollRoot)) out.add(pollRoot.resolve(rf.relativePath()).toFile());
        return out;
    }

    /**
     * Would this remote file be skipped as a known duplicate <em>before</em> spending bandwidth to fetch it?
     * PATH dedup: a marker for its staging location already exists. METADATA dedup: the listing's size+mtime
     * match the ledger fingerprint. CHECKSUM can't decide without the bytes, so it never pre-skips (the file is
     * fetched and {@link #ledgerFilter} decides on content). Dedup off ⇒ never a duplicate.
     */
    private static boolean isKnownDuplicate(PipelineConfig cfg, RemoteFile rf, Path pollRoot) {
        if (!cfg.processing().duplicateCheckEnabled()) return false;
        PipelineConfig.Duplicate dup = cfg.source().duplicate();
        if (!dup.contentBased())
            return MarkerManager.isAlreadyProcessed(pollRoot.resolve(rf.relativePath()).toFile(), cfg);
        if (DuplicatePolicy.Mode.from(dup.mode()) == DuplicatePolicy.Mode.METADATA
                && rf.hasSize() && rf.lastModified() != null) {
            LedgerEntry prior = AcquisitionLedgers.shared().find(cfg.source().id(), rf.relativePath()).orElse(null);
            return DuplicatePolicy.decide(DuplicatePolicy.Mode.METADATA, prior, rf.size(),
                    rf.lastModified().toEpochMilli(), null) == DuplicatePolicy.Decision.DUPLICATE;
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

    private static List<File> toFiles(List<RemoteFile> ready) {
        List<File> out = new ArrayList<>(ready.size());
        for (RemoteFile rf : ready) out.add(rf.localPath().toFile());
        return out;
    }

    /**
     * Content-based duplicate filter (Phase C): for each candidate, look up its prior fingerprint in the
     * {@link AcquisitionLedger} by {@code (sourceId, relativePath)} and apply {@link DuplicatePolicy}. DUPLICATEs
     * are dropped; CHANGED files are reprocessed unless {@code on_change=ignore}; the fingerprint is recorded
     * post-commit by {@code BatchProcessor}.
     *
     * <p><b>METADATA</b> compares size+mtime — a cheap {@code stat}, no file read, so it runs on both the run
     * cycle and the read-only {@code countPending} scan. <b>CHECKSUM</b> must read the file to hash it; that
     * happens <em>only on the run path</em> ({@code emitSignals}), and the hash is stashed for the post-commit
     * record so the file isn't hashed twice. On {@code countPending} CHECKSUM degrades to a metadata
     * approximation, so a dashboard poll never hashes.
     */
    private static List<File> ledgerFilter(PipelineConfig cfg, List<RemoteFile> ready, boolean emitSignals)
            throws java.io.IOException {
        PipelineConfig.Source src = cfg.source();
        PipelineConfig.Duplicate dup = src.duplicate();
        AcquisitionLedger ledger = AcquisitionLedgers.shared();
        DuplicatePolicy.Mode mode = DuplicatePolicy.Mode.from(dup.mode());
        DuplicatePolicy.OnChange onChange = DuplicatePolicy.OnChange.from(dup.onChange());
        boolean checksum = mode == DuplicatePolicy.Mode.CHECKSUM;

        List<File> out = new ArrayList<>(ready.size());
        for (RemoteFile rf : ready) {
            Path p = rf.localPath();
            long size, mtime;
            try {
                size = Files.size(p);
                mtime = Files.getLastModifiedTime(p).toMillis();
            } catch (java.io.IOException vanished) {
                continue;   // file disappeared between discovery and the dedup check — drop it
            }
            LedgerEntry prior = ledger.find(src.id(), rf.relativePath()).orElse(null);

            // CHECKSUM hashes only on the run path; countPending falls back to a cheap metadata approximation.
            String cs = null;
            DuplicatePolicy.Mode decideMode = mode;
            if (checksum) {
                if (emitSignals) cs = Checksums.of(p, dup.algorithm());
                else decideMode = DuplicatePolicy.Mode.METADATA;
            }
            switch (DuplicatePolicy.decide(decideMode, prior, size, mtime, cs)) {
                case NEW -> {
                    if (checksum && emitSignals) AcquisitionLedgers.stashChecksum(p, cs);
                    out.add(p.toFile());
                }
                case CHANGED -> {
                    if (emitSignals && DuplicatePolicy.alertsOnChange(onChange)) AcquisitionTelemetry.emitFileChanged(cfg, rf);
                    if (DuplicatePolicy.reprocessOnChange(onChange)) {
                        if (checksum && emitSignals) AcquisitionLedgers.stashChecksum(p, cs);
                        out.add(p.toFile());
                    } else if (emitSignals) {
                        AcquisitionTelemetry.incDuplicatesSkipped(cfg);
                    }
                }
                case DUPLICATE -> { if (emitSignals) AcquisitionTelemetry.incDuplicatesSkipped(cfg); }
            }
        }
        return out;
    }

    /**
     * Incremental high-watermark filter (Phase C4): drop candidates whose modification time is strictly older
     * than the source's high-watermark — the greatest {@code last_modified} the {@link AcquisitionLedger} has
     * recorded for this source. A single cheap aggregate lookup, no file read, so it runs on both the run cycle
     * and the read-only {@code countPending} scan; the {@code inspecto_watermark_skipped_total} counter bumps on
     * the run path only.
     *
     * <p>Remote listings carry {@code lastModified}; a local file leaves it {@code null} at discovery (see
     * {@link RemoteFile}), so its mtime is {@code stat}'d here on demand. A file whose mtime cannot be
     * determined is kept (never skipped) — correctness over the optimisation. The frontier ({@code == watermark})
     * passes through to the ledger for exact dedup, so the newest slice is never blindly dropped.
     */
    private static List<RemoteFile> watermarkFilter(PipelineConfig cfg, List<RemoteFile> ready, boolean emitSignals) {
        java.util.OptionalLong wm = AcquisitionLedgers.shared().highWatermark(cfg.source().id());
        if (wm.isEmpty()) return ready;            // nothing recorded yet — the first run sees everything
        long watermark = wm.getAsLong();
        List<RemoteFile> out = new ArrayList<>(ready.size());
        int skipped = 0;
        for (RemoteFile rf : ready) {
            java.util.OptionalLong mtime = effectiveMtime(rf);
            if (mtime.isPresent() && mtime.getAsLong() < watermark) skipped++;
            else out.add(rf);
        }
        if (emitSignals && skipped > 0)
            MetricRegistry.global().inc("inspecto_watermark_skipped_total",
                    "Files skipped by the incremental high-watermark",
                    Map.of("pipeline", cfg.identity().pipelineName()), skipped);
        return out;
    }

    /** The file's modification time in epoch millis for the watermark check: from the listing if the connector
     *  supplied it, else {@code stat}'d from the local copy, else empty (mtime unknown ⇒ never skipped). */
    private static java.util.OptionalLong effectiveMtime(RemoteFile rf) {
        if (rf.lastModified() != null) return java.util.OptionalLong.of(rf.lastModified().toEpochMilli());
        if (rf.isLocal()) {
            try {
                return java.util.OptionalLong.of(Files.getLastModifiedTime(rf.localPath()).toMillis());
            } catch (java.io.IOException statFailed) {
                return java.util.OptionalLong.empty();
            }
        }
        return java.util.OptionalLong.empty();
    }

    /**
     * Sequence-gap detection (Phase D): run {@link GapDetector} over the discovered file names and emit a
     * {@link EventType#SEQUENCE_GAP} event + bump {@code inspecto_sequence_gaps_total} for each <em>newly</em>
     * missing key. {@link GapTracker} suppresses re-firing a persistent gap on every poll cycle and forgets a
     * gap once its file lands. A malformed sequence template is logged and skipped (never disturbs ingest).
     */
    private static void detectGaps(PipelineConfig cfg, List<RemoteFile> discovered) {
        PipelineConfig.GapDetection gd = cfg.source().gapDetection();
        List<String> names = new ArrayList<>(discovered.size());
        for (RemoteFile f : discovered) names.add(f.name());

        final GapDetector.GapReport report;
        try {
            report = GapDetector.findGaps(gd.sequence(), names);
        } catch (RuntimeException badTemplate) {
            log.warn("Gap detection skipped for {}: {}", cfg.identity().pipelineName(), badTemplate.getMessage());
            return;
        }
        // Reconcile against what was already reported: only fire for keys newly missing this cycle.
        List<String> fresh = GapTracker.shared().newGaps(cfg.source().id(), report.missing());
        if (fresh.isEmpty()) return;

        MetricRegistry.global().inc("inspecto_sequence_gaps_total", "Missing files detected in a configured sequence",
                Map.of("pipeline", cfg.identity().pipelineName()), fresh.size());
        for (String key : fresh) AcquisitionTelemetry.emitSequenceGap(cfg, key, gd.sequence(), report.unit().name());
        log.warn("Sequence gap(s) for {}: {} missing key(s) in '{}' — {}",
                cfg.identity().pipelineName(), fresh.size(), gd.sequence(), fresh);
    }

    /** Count of {@link #collectCandidates(PipelineConfig) pending} inbox files; {@code -1} if the scan fails. */
    public static int countPending(PipelineConfig cfg) {
        try {
            return collectCandidates(cfg).size();
        } catch (java.io.IOException e) {
            log.warn("Pending scan failed for {}: {}", cfg.identity().pipelineName(), e.getMessage());
            return -1;
        }
    }

    /**
     * Thrown by {@link #run(PipelineConfig)} when one or more batches failed.
     * {@link #main(String[])} catches this and exits with a non-zero status so
     * wrapper scripts can detect partial-failure runs without scraping logs.
     */
    public static final class BatchProcessingException extends RuntimeException {
        public final int failedBatches;
        public final int totalBatches;
        BatchProcessingException(int failed, int total) {
            super(failed + " of " + total + " batch(es) failed; see logs for details");
            this.failedBatches = failed;
            this.totalBatches  = total;
        }
    }
}
