package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.Checksums;
import com.gamma.acquire.CircuitBreaker;
import com.gamma.acquire.DiscoveryContext;
import com.gamma.acquire.DuplicatePolicy;
import com.gamma.acquire.GapDetector;
import com.gamma.acquire.GapTracker;
import com.gamma.acquire.LedgerEntry;
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
import org.slf4j.MDC;

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

        // Propagate the caller's space (MDC) onto each batch worker: the commit listener (the service's event-bus
        // sink), the per-batch metrics and the event log all fire on these threads, so without this their per-space
        // routing would fall back to "default". Mirrors MultiSourceProcessor.runAll/runConfigs (Stage 3a).
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Batch b : batches) {
                futures.add(executor.submit(() -> {
                    if (mdc != null) MDC.setContextMap(mdc);
                    try {
                        permits.acquire();
                        try {
                            BatchProcessor.process(b, cfg, audit);
                        } finally {
                            permits.release();
                        }
                    } finally {
                        MDC.clear();
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
                if (!emitSignals) return RemoteAcquisitionHandler.pendingRemoteApprox(cfg, ready);
                ready = RemoteAcquisitionHandler.materializeRemote(cfg, connector, ready, retry);
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
