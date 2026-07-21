package com.gamma.service;

import com.gamma.etl.BatchEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.MultiCollectorProcessor;
import com.gamma.pipeline.PipelineTrigger;
import com.gamma.pipeline.exec.TriggerCoalescer;
import com.gamma.util.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The scheduled + event-driven ingest driver, extracted from {@link CollectorService} (M2 step 2,
 * modularization-optimization-plan §2.2.1a). It owns the poll-cycle body ({@link #runCycle()}), the
 * T13 entry-node trigger gating (interval / cron — {@link #dueThisTick}), and the event-trigger
 * hand-off ({@link #onUpstreamCommit}) — the "loop" half of the former god object.
 *
 * <h3>Shared state, not owned</h3>
 * The scheduler does <b>not</b> own the ingest lock, config registry, run budget, event bus, or the
 * pipeline sets — those are shared with {@link CollectorService} and passed in <em>by reference</em>.
 * Two contracts are load-bearing:
 * <ul>
 *   <li><b>One {@code ingestLock}.</b> {@link #runCycle()} must lock the <em>same</em>
 *       {@link ReentrantLock} instance the operator-trigger paths ({@code CollectorService.runPipeline}
 *       / {@code register}/{@code unregisterPipeline}) lock. A cloned lock compiles fine but lets a
 *       manual run overlap a cycle — a silent live deadlock / data race. Pinned by
 *       {@code CollectorServiceIngestLockTest}.</li>
 *   <li><b>Off-thread event hand-off.</b> {@link BatchEventBus#publish} is synchronous on the
 *       publishing (lock-holding) thread; {@link #onUpstreamCommit} therefore hands the triggered run
 *       to {@link #triggerWorkers} (a virtual thread) instead of running it inline, or it would
 *       self-deadlock on {@code ingestLock}. Pinned by {@code CollectorServiceTriggerTest}.</li>
 * </ul>
 * The caller applies the per-space MDC ({@code CollectorService.underSpace}) around {@link #runCycle()},
 * and the {@code runPipeline} callback re-applies it on the trigger path, so this class needs no MDC of
 * its own.
 */
final class PipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineScheduler.class);

    // ── Shared references (owned by CollectorService) ────────────────────────────
    private final List<Path> registry;
    private final ConfigRegistry configRegistry;
    private final Set<String> paused;
    private final Set<String> running;
    private final ReentrantLock ingestLock;
    private final BatchEventBus bus;
    private final ExecutorService triggerWorkers;
    private final int maxConcurrentRuns;
    /** Run one registered pipeline by name (stays on {@link CollectorService}; locks the same ingestLock). */
    private final Consumer<String> runPipeline;
    /** Project the on-disk audit into the DB status store, if DB-backed (stays on {@link CollectorService}). */
    private final Runnable syncStatus;

    // ── Owned by the scheduler ───────────────────────────────────────────────────
    /** T13 / §3.8 — per-pipeline last-run epoch (ms); gates a {@code schedule:{every}}/{@code cron} pipeline
     *  by its own cadence instead of running every active flow each tick. A pipeline with no {@code trigger:}
     *  is {@code DEFAULT_POLL} and still runs every cycle. */
    private final Map<String, Long> lastRunAtMs = new ConcurrentHashMap<>();
    /** Per-pipeline coalescer for {@code event}-triggered flows: an upstream-commit storm collapses to one
     *  non-overlapping run (the in-process {@code ingestLock} debounce, lifted to the flow grain). */
    private final Map<String, TriggerCoalescer> eventCoalescers = new ConcurrentHashMap<>();
    /** Zone for evaluating {@code cron} triggers (mirrors {@link com.gamma.job.JobService}). */
    private final ZoneId triggerZone = ZoneId.systemDefault();
    /** Epoch the scheduler started — the cron "last fire" baseline before a pipeline has ever run. */
    private final long serviceStartMs = System.currentTimeMillis();

    PipelineScheduler(List<Path> registry, ConfigRegistry configRegistry, Set<String> paused,
                      Set<String> running, ReentrantLock ingestLock, BatchEventBus bus,
                      ExecutorService triggerWorkers, int maxConcurrentRuns,
                      Consumer<String> runPipeline, Runnable syncStatus) {
        this.registry          = registry;
        this.configRegistry    = configRegistry;
        this.paused            = paused;
        this.running           = running;
        this.ingestLock        = ingestLock;
        this.bus               = bus;
        this.triggerWorkers    = triggerWorkers;
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.runPipeline       = runPipeline;
        this.syncStatus        = syncStatus;
    }

    /**
     * Run every registered, due pipeline once, concurrently (bounded by the global budget), feeding
     * committed-batch events to the bus. The caller ({@code CollectorService.runAllOnce}) has already
     * applied this space's MDC, so the cycle and its parallel workers run under the right space.
     *
     * @return the run outcome (total / failed source counts)
     */
    MultiCollectorProcessor.RunResult runCycle() {
        ingestLock.lock();   // never overlap with another cycle / operator trigger (see class doc)
        try {
            // Re-index configs once per cycle — an mtime-cached rebuild, so a steady-state cycle re-parses
            // nothing; an edited pipeline/schema reloads on the next tick. Fires catalog invalidation via
            // the registry callback.
            configRegistry.rebuild(registry);
            // Build the run set from the cached index: skip paused pipelines and any not yet activated
            // (`active: true`). Each runnable config is re-stamped with a fresh run timestamp for this cycle
            // (cheap copy; no re-parse). Iterate the registered paths (not the id-keyed index) so two files
            // are both run even if they declare the same name — matching the prior path-level run semantics.
            long nowMs = System.currentTimeMillis();
            List<PipelineConfig> toRun = new ArrayList<>();
            List<String> activeNames  = new ArrayList<>();
            for (Path p : registry) {
                PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
                if (cfg == null) continue;                                   // unloadable — already warned
                String id = cfg.identity().pipelineName();
                if (paused.contains(id) || !cfg.active()) continue;          // paused or not activated
                if (!dueThisTick(cfg, id, nowMs)) continue;                  // T13: trigger gates the loop
                toRun.add(cfg.forNewRun());                                  // fresh per-cycle timestamp
                activeNames.add(id);
            }
            if (toRun.isEmpty()) return new MultiCollectorProcessor.RunResult(0, 0);
            activeNames.forEach(id -> lastRunAtMs.put(id, nowMs));           // stamp cadence baseline (start-to-start)
            com.gamma.metrics.MetricRegistry reg = com.gamma.metrics.MetricRegistry.global();
            reg.inc("inspecto_poll_cycles_total", "Poll cycles run", Map.of());
            reg.setGauge("inspecto_active_runs", "Source runs currently executing", Map.of(), toRun.size());
            running.addAll(activeNames);
            MultiCollectorProcessor.RunResult r;
            try {
                r = MultiCollectorProcessor.runConfigs(toRun, maxConcurrentRuns, bus.sink());
                if (r.failed() > 0) {
                    log.warn("Poll cycle: {} of {} source(s) failed", r.failed(), r.total());
                    reg.inc("inspecto_source_run_failures_total", "Source-run failures", Map.of(), r.failed());
                }
            } finally {
                running.removeAll(activeNames);
                reg.setGauge("inspecto_active_runs", "Source runs currently executing", Map.of(), 0);
            }
            // Refresh the status DB (if DB-backed) so this cycle's commits are queryable.
            syncStatus.run();
            return r;
        } finally {
            // (Catalog invalidation already fired from configRegistry.rebuild at the top of the cycle.)
            ingestLock.unlock();
        }
    }

    /**
     * Whether the loop scheduler should run {@code cfg} on this tick, per its entry-node trigger (T13 / §3.8):
     * <ul>
     *   <li><b>absent / {@code DEFAULT_POLL}</b> — every tick (unchanged from the pre-T13 poll-all behaviour);</li>
     *   <li><b>{@code schedule:{every:N}}</b> — only once {@code N} has elapsed since the last run;</li>
     *   <li><b>{@code schedule:{cron}}</b> — only when a cron fire is due since the last run;</li>
     *   <li><b>{@code event} / {@code manual}</b> — never on the loop; driven by {@link #onUpstreamCommit}
     *       and {@code CollectorService.runPipeline} respectively.</li>
     * </ul>
     */
    private boolean dueThisTick(PipelineConfig cfg, String id, long nowMs) {
        PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
        return switch (t.scheduler()) {
            case EVENT, MANUAL -> false;                       // driven off the poll loop
            case LOOP -> switch (t.kind()) {
                case SCHEDULE_INTERVAL -> {
                    Long last = lastRunAtMs.get(id);
                    yield last == null || (nowMs - last) >= t.everyMs();
                }
                case SCHEDULE_CRON -> cronDue(id, t.cron(), nowMs);
                default -> true;                               // DEFAULT_POLL — every tick (today's behaviour)
            };
        };
    }

    /** A cron trigger is due when its next fire after the last run (or service start) is at/​before now. */
    private boolean cronDue(String id, String cron, long nowMs) {
        try {
            long lastMs = lastRunAtMs.getOrDefault(id, serviceStartMs);
            ZonedDateTime from = Instant.ofEpochMilli(lastMs).atZone(triggerZone);
            ZonedDateTime next = CronExpression.parse(cron).next(from);
            return !next.isAfter(Instant.ofEpochMilli(nowMs).atZone(triggerZone));   // next <= now ⇒ fire due
        } catch (Exception e) {
            log.warn("Pipeline '{}' has an invalid cron trigger '{}' — skipping this cycle: {}",
                    id, cron, e.getMessage());
            return false;
        }
    }

    /**
     * Bus listener (T13 / §3.8): an upstream SUCCESS commit triggers every {@code event}-triggered flow whose
     * {@code from} names that upstream. The run is handed to {@link #triggerWorkers} (the bus delivers on the
     * publishing thread under {@code ingestLock}; running inline would deadlock) and coalesced per flow so an
     * upstream storm collapses to one non-overlapping run.
     */
    void onUpstreamCommit(BatchEvent event) {
        if (!"SUCCESS".equals(event.status())) return;
        for (Path p : registry) {
            PipelineConfig cfg = configRegistry.configForPath(p).orElse(null);
            if (cfg == null) continue;
            String id = cfg.identity().pipelineName();
            if (paused.contains(id) || !cfg.active()) continue;
            if (id.equals(event.pipeline())) continue;                       // self-loop guard
            PipelineTrigger t = PipelineTrigger.of(cfg.triggerConfig());
            if (t.scheduler() != PipelineTrigger.Scheduler.EVENT) continue;
            if (!triggerMatches(t.from(), event.pipeline())) continue;
            TriggerCoalescer coalescer = eventCoalescers.computeIfAbsent(id, k -> new TriggerCoalescer());
            triggerWorkers.submit(() -> coalescer.signal(() -> runPipeline.accept(id)));
        }
    }

    /**
     * Whether an event trigger's {@code from} names {@code upstream}. Matches case-insensitively against the
     * emitted pipeline id (lowercased {@code pipelineName}) and tolerates a {@code flows/<id>} prefix, so an
     * operator may write {@code from: orders}, {@code from: ORDERS}, or {@code from: flows/orders}.
     */
    private static boolean triggerMatches(String from, String upstream) {
        if (from == null || from.isBlank() || upstream == null) return false;
        String f = from.trim().toLowerCase();
        String u = upstream.toLowerCase();
        return f.equals(u) || f.endsWith("/" + u);
    }

    /**
     * Reset a pipeline's cadence baseline after an operator-/watch-triggered run (start-to-start), so a
     * {@code schedule:{every}}/{@code cron} pipeline's next loop tick is measured from this run. Called by
     * {@code CollectorService.runPipeline}, which owns the sync trigger path but shares this scheduler's
     * cadence map.
     */
    void recordManualRun(String id, long nowMs) {
        lastRunAtMs.put(id, nowMs);
    }

    /**
     * Drop a pipeline's scheduler bookkeeping when it is unregistered. Without this, the cadence
     * ({@link #lastRunAtMs}) and coalescer ({@link #eventCoalescers}) maps accumulate one orphan entry
     * per deleted pipeline for the lifetime of the space's service — a slow leak under pipeline churn.
     * The {@link TriggerCoalescer} holds only in-heap atomics, so dropping the reference is enough.
     */
    void forget(String id) {
        lastRunAtMs.remove(id);
        eventCoalescers.remove(id);
    }
}
