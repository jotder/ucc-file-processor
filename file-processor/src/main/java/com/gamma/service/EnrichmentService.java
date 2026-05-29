package com.gamma.service;

import com.gamma.api.PublicApi;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.enrich.EnrichmentEngine;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PartitionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates Stage-2 {@link EnrichmentEngine} runs against the platform's two
 * triggers — the flagship "incremental + scheduled" model. It is the composition
 * point that binds the pure enrichment engine ({@code com.gamma.enrich}) to the
 * service's {@link BatchEventBus} (freshness) and {@link Scheduler} (completeness).
 *
 * <h3>Triggers</h3>
 * <ul>
 *   <li><b>Event (freshness)</b> — subscribes to the bus. When a batch commits for a
 *       job's {@code triggers.on_pipeline}, the job recomputes <em>only the partitions
 *       that batch wrote</em> ({@link BatchEvent#partitions()} → input filter). This is
 *       the cheap, near-real-time path.</li>
 *   <li><b>Scheduled (completeness)</b> — for jobs with {@code triggers.schedule_seconds},
 *       registers an interval job that recomputes the <em>full</em> window. Idempotent
 *       overwrite reconciles late-arriving data the event path may have missed.</li>
 * </ul>
 *
 * <h3>Chains</h3>
 * After a successful recompute a job publishes its own {@link BatchEvent} (pipeline =
 * the job's {@code name}, partitions = what it wrote) back onto the bus. A downstream
 * job whose {@code on_pipeline} equals that name therefore fires automatically — Stage-2
 * → Stage-2 chains use the very same machinery. A job is never triggered by its own
 * output (guarded), so an accidental self-reference can't loop.
 *
 * <h3>Concurrency &amp; idempotency</h3>
 * Bus listeners run on the publishing (ingest) thread, so event recomputes are handed
 * to an internal virtual-thread executor — heavy DuckDB work never blocks ingest. A
 * per-job lock serialises that job's recomputes, so an event and a scheduled run for the
 * same job can't race on the same output partitions; different jobs still run in parallel.
 * {@code OVERWRITE_OR_IGNORE} writes make any overlap converge to the same result.
 *
 * <p>The {@link Scheduler} is <b>borrowed</b> (owned by the hosting {@link SourceService});
 * {@link #close()} shuts down only the executor this service created.
 */
@PublicApi(since = "2.3.0")
public final class EnrichmentService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);

    private final List<EnrichmentConfig> jobs;
    private final BatchEventBus bus;
    private final Scheduler scheduler;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public EnrichmentService(List<EnrichmentConfig> jobs, BatchEventBus bus, Scheduler scheduler) {
        this.jobs      = List.copyOf(jobs);
        this.bus       = bus;
        this.scheduler = scheduler;
    }

    /** Wire the event subscriber and register scheduled completeness jobs. */
    public void start() {
        bus.subscribe(this::onBatchEvent);
        int events = 0, scheduled = 0;
        for (EnrichmentConfig job : jobs) {
            if (job.triggers().hasEvent()) events++;
            if (job.triggers().hasSchedule()) {
                long s = job.triggers().scheduleSeconds();
                // initialDelay = interval so the timer (not an immediate run) drives this path
                scheduler.everySeconds("enrich-" + job.name(), s, s,
                        () -> recompute(job, null, "schedule"));
                scheduled++;
            }
        }
        log.info("EnrichmentService started: {} job(s) — {} event-triggered, {} scheduled",
                jobs.size(), events, scheduled);
    }

    /** Dispatch a committed-batch event to any job listening on its pipeline. */
    private void onBatchEvent(BatchEvent event) {
        for (EnrichmentConfig job : jobs) {
            EnrichmentConfig.Triggers t = job.triggers();
            if (!t.hasEvent()) continue;
            if (!t.onPipeline().equals(event.pipeline())) continue;
            if (job.name().equals(event.pipeline())) continue;   // self-loop guard
            List<Map<String, String>> filter = toFilter(job, event.partitions());
            // hand off — never block the publishing (ingest) thread on DuckDB work
            workers.submit(() -> recompute(job, filter, "event:" + event.pipeline()));
        }
    }

    /**
     * Run one enrichment recompute (full when {@code filter} is null/empty, else scoped),
     * then announce it on the bus so downstream chained jobs can react. Serialised per job.
     */
    private void recompute(EnrichmentConfig job, List<Map<String, String>> filter, String reason) {
        ReentrantLock lock = locks.computeIfAbsent(job.name(), k -> new ReentrantLock());
        lock.lock();
        try {
            List<PartitionOutput> outs = EnrichmentEngine.run(job, filter);
            List<String> parts = outs.stream().map(PartitionOutput::partition).distinct().toList();
            log.info("[ENRICH] {} recomputed ({}) → {} partition file(s)", job.name(), reason, outs.size());
            // chain: a successful enrichment is itself a commit downstream jobs can subscribe to
            bus.publish(new BatchEvent(job.name(), job.name() + "-" + seq.incrementAndGet(),
                    "SUCCESS", parts, 0L));
        } catch (Exception e) {
            log.error("[ENRICH] {} recompute failed ({})", job.name(), reason, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Translate committed partition paths ({@code col=val/col=val/...}) into the engine's
     * filter form, keeping only columns this job partitions its input by. If a path shares
     * no partition column with the job it is dropped; an empty result means "recompute
     * fully" — the safe fallback when the event can't be scoped.
     */
    private static List<Map<String, String>> toFilter(EnrichmentConfig job, List<String> partitionPaths) {
        Set<String> cols = new HashSet<>(job.input().partitions());
        List<Map<String, String>> out = new ArrayList<>();
        for (String path : partitionPaths) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String kv : path.split("/")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String k = kv.substring(0, eq).trim();
                    if (cols.contains(k)) m.put(k, kv.substring(eq + 1).trim());
                }
            }
            if (!m.isEmpty()) out.add(m);
        }
        return out;
    }

    @Override
    public void close() {
        workers.close();   // virtual-thread executor: awaits in-flight recomputes
        log.info("EnrichmentService stopped");
    }
}
