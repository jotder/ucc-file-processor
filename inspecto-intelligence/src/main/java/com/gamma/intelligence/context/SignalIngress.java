package com.gamma.intelligence.context;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Canonical-ledger ingress for triage (S5, additive-only — deliberately does NOT replace
 * {@code FailureReactor}, which stays on the legacy {@code BatchEventBus}): a live
 * {@link EventLog} subscriber that keeps a small in-memory window of recent <em>elevated</em>
 * signals (severity ≥ ERROR, or the failure types {@code pipeline.batch.failed} /
 * {@code job.run.failed}) for the deliberative layer to read via {@link #recent(int)}.
 *
 * <h3>Never block the emitting thread</h3>
 * {@link EventLog} subscribers run synchronously on the emitting thread, which may hold
 * {@code ingestLock}. The subscriber therefore only filters on the event type constant, offers to
 * a bounded queue (shedding, never blocking, when full) and hands off to its own daemon
 * virtual-thread executor — exactly {@code FailureReactor}'s queue+hand-off pattern. All Signal
 * reconstruction and filtering happens on the executor, off the emitting path.
 */
public final class SignalIngress implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SignalIngress.class);

    /** Bounded hand-off queue: a signal flood sheds (drops + counts) rather than growing memory. */
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;
    /** How many elevated signals the triage window retains (oldest evicted first). */
    public static final int RETAIN = 64;

    private final Executor executor;
    private final ExecutorService ownedPool; // non-null only when this ingress created the pool
    private final BlockingQueue<Event> queue;
    private final Deque<Signal> recent = new ArrayDeque<>(); // guarded by synchronized(recent)
    private final AtomicLong dropped = new AtomicLong();
    private final Consumer<Event> subscriber = this::onEvent;
    private volatile EventLog attached;

    /** Production ingress: an owned daemon virtual-thread-per-task executor, default capacity. */
    public SignalIngress() {
        this(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("inspecto-triage-", 0).factory()),
                DEFAULT_QUEUE_CAPACITY, true);
    }

    /** Test seam: inject an executor (e.g. {@code Runnable::run} for a synchronous, deterministic
     *  drain) and a queue capacity. The executor is not owned — {@link #close()} won't shut it down. */
    SignalIngress(Executor executor, int queueCapacity) {
        this(executor, queueCapacity, false);
    }

    private SignalIngress(Executor executor, int queueCapacity, boolean owned) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        this.executor = executor;
        this.ownedPool = owned ? (ExecutorService) executor : null;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    /** Register on the live log. Idempotent enough for the single-attach lifecycle we use. */
    public void attach(EventLog eventLog) {
        this.attached = eventLog;
        eventLog.addSubscriber(subscriber);
    }

    /** The subscriber body — minimum work on the emitting thread: type check, offer, hand off. */
    private void onEvent(Event event) {
        if (event == null || !EventType.SIGNAL.equals(event.type())) return;
        if (!queue.offer(event)) {
            log.warn("triage ingress queue full; dropped signal event (total dropped={})",
                    dropped.incrementAndGet());
            return;
        }
        try {
            executor.execute(this::drainOne);
        } catch (RejectedExecutionException shuttingDown) {
            queue.poll(); // couldn't schedule (closing) — undo the enqueue
        }
    }

    /** Reconstruct + filter exactly one queued event (runs on the executor, off the emit path). */
    private void drainOne() {
        Event e = queue.poll();
        if (e == null) return;
        try {
            Signal s = Signal.fromEvent(e);
            if (!elevated(s)) return;
            synchronized (recent) {
                recent.addFirst(s);
                while (recent.size() > RETAIN) recent.removeLast();
            }
        } catch (RuntimeException ex) {
            log.warn("triage ingress failed to process a signal event: {}", ex.getMessage());
        }
    }

    /** Severity ≥ ERROR, or one of the canonical failure types. */
    static boolean elevated(Signal s) {
        return s.severity().ordinal() >= Severity.ERROR.ordinal()
                || Signals.matchesType(s.type(), "pipeline.batch.failed")
                || Signals.matchesType(s.type(), "job.run.failed");
    }

    /** The most recent elevated signals, newest first, at most {@code limit}. */
    public List<Signal> recent(int limit) {
        synchronized (recent) {
            List<Signal> out = new ArrayList<>(Math.min(limit, recent.size()));
            for (Signal s : recent) {
                if (out.size() >= limit) break;
                out.add(s);
            }
            return out;
        }
    }

    /** Count of events shed because the hand-off queue was full (diagnostics/tests). */
    public long droppedCount() {
        return dropped.get();
    }

    /** De-register from the log and shut down the owned executor (no-op for an injected one). */
    @Override
    public void close() {
        EventLog eventLog = attached;
        if (eventLog != null) eventLog.removeSubscriber(subscriber);
        if (ownedPool != null) ownedPool.shutdown();
    }
}
