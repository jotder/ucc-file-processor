package com.gamma.agent.diagnose;

import com.gamma.assist.Diagnosis;
import com.gamma.etl.BatchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The event-driven failure-diagnosis reactor (v3.7.0, M7). It subscribes to the core
 * {@code BatchEventBus} (via {@code eventBus().subscribe(reactor::onEvent)}) and, for every
 * <em>FAILED</em> batch, produces a {@link Diagnosis} into a {@link DiagnosisStore}.
 *
 * <h3>Never block the ingest thread</h3>
 * Bus listeners run <b>synchronously on the publishing (ingest) thread</b>, so a slow AI diagnosis
 * there would throttle ingest. {@link #onEvent} therefore does the minimum — filter, enqueue
 * (non-blocking {@code offer}), schedule a drain on its own {@link Executor}, and return. The actual
 * diagnosis (heuristic + optional model call) happens on the executor, off the ingest path. This is
 * the queue-backed, virtual-thread hand-off the design calls for (design_analysis.md §4.C, V-10).
 *
 * <p>The queue is bounded: under a flood of failures it sheds load (drops + logs) rather than growing
 * without limit. The default executor is a daemon virtual-thread-per-task pool, so diagnosis work
 * never competes with ETL batch workers and never keeps the JVM alive on shutdown.
 *
 * <h3>Abstain-safe</h3>
 * The {@link Diagnoser} is supplied by the caller — the agent wires one that always runs the
 * deterministic {@link HeuristicDiagnoser} and only calls a model when one is available. So the
 * reactor does useful work with zero model I/O on a CPU-only / disabled deployment.
 */
public final class FailureReactor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FailureReactor.class);

    /** Bounded so a burst of failures can't grow memory without limit; excess events are dropped + logged. */
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;

    /** Turns a failed batch event into a diagnosis (heuristic, optionally model-enriched). */
    @FunctionalInterface
    public interface Diagnoser {
        Diagnosis diagnose(BatchEvent event);
    }

    private final Executor executor;
    private final ExecutorService ownedPool;   // non-null only when this reactor created the pool
    private final BlockingQueue<BatchEvent> queue;
    private final Diagnoser diagnoser;
    private final DiagnosisStore store;
    private final Consumer<Diagnosis> onDiagnosed;   // audit sink; may be null
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Production reactor: a daemon virtual-thread-per-task executor owned by this reactor (shut down
     * on {@link #close()}) and the default queue capacity.
     */
    public FailureReactor(Diagnoser diagnoser, DiagnosisStore store, Consumer<Diagnosis> onDiagnosed) {
        this(newDaemonVirtualThreadPool(), DEFAULT_QUEUE_CAPACITY, diagnoser, store, onDiagnosed, true);
    }

    /** Production reactor with an explicit queue capacity (v4.1 tunable: {@code assist.reactor.queue}). */
    public FailureReactor(Diagnoser diagnoser, DiagnosisStore store, Consumer<Diagnosis> onDiagnosed,
                          int queueCapacity) {
        this(newDaemonVirtualThreadPool(), queueCapacity, diagnoser, store, onDiagnosed, true);
    }

    /**
     * Test/seam constructor: inject an {@link Executor} (e.g. {@code Runnable::run} to drain
     * synchronously for deterministic tests) and a queue capacity. The executor is <b>not</b> owned —
     * {@link #close()} won't shut it down.
     */
    FailureReactor(Executor executor, int queueCapacity, Diagnoser diagnoser,
                   DiagnosisStore store, Consumer<Diagnosis> onDiagnosed) {
        this(executor, queueCapacity, diagnoser, store, onDiagnosed, false);
    }

    private FailureReactor(Executor executor, int queueCapacity, Diagnoser diagnoser,
                           DiagnosisStore store, Consumer<Diagnosis> onDiagnosed, boolean owned) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        this.executor = executor;
        this.ownedPool = owned ? (ExecutorService) executor : null;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.diagnoser = diagnoser;
        this.store = store;
        this.onDiagnosed = onDiagnosed;
    }

    private static ExecutorService newDaemonVirtualThreadPool() {
        // Virtual threads are always daemon, so an un-shut-down pool never blocks JVM exit.
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ucc-diagnose-", 0).factory());
    }

    /**
     * The bus subscriber. Non-blocking: filters non-FAILED events, enqueues, and schedules the
     * diagnosis on the executor — then returns immediately so the ingest thread is never held.
     */
    public void onEvent(BatchEvent event) {
        if (event == null || !"FAILED".equalsIgnoreCase(event.status())) return;
        if (!queue.offer(event)) {
            log.warn("diagnosis queue full; dropped FAILED event for batch {} (total dropped={})",
                    event.batchId(), dropped.incrementAndGet());
            return;
        }
        try {
            executor.execute(this::drainOne);
        } catch (RejectedExecutionException shuttingDown) {
            queue.poll();   // couldn't schedule (closing) — undo the enqueue so the queue stays accurate
        }
    }

    /** Diagnose exactly one queued event (runs on the executor, off the ingest thread). */
    private void drainOne() {
        BatchEvent e = queue.poll();
        if (e == null) return;
        try {
            Diagnosis d = diagnoser.diagnose(e);
            if (d != null) {
                store.add(d);
                if (onDiagnosed != null) onDiagnosed.accept(d);
            }
        } catch (Exception ex) {
            log.warn("failure diagnosis errored for batch {} (pipeline {})", e.batchId(), e.pipeline(), ex);
        }
    }

    /** Count of events shed because the queue was full (diagnostics/tests). */
    public long droppedCount() {
        return dropped.get();
    }

    /** Shut down the owned executor (no-op for an injected one). */
    @Override
    public void close() {
        if (ownedPool != null) ownedPool.shutdown();
    }
}
