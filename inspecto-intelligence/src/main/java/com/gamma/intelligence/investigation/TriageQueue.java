package com.gamma.intelligence.investigation;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Autonomous triage ingress (AGT-5 P1 slice E, D3): a live {@link EventLog} subscriber that turns an
 * error/critical Signal into an investigation, running the RCA playbook off the emitting thread. It
 * rides the canonical Signal bus (not the legacy {@code BatchEventBus}, which stays with
 * {@code FailureReactor}), parallel to {@link com.gamma.intelligence.context.SignalIngress} but with
 * dispatcher semantics: dedupe by correlationId, an {@code agent.*} exclusion (so the agent never
 * investigates its own telemetry — a feedback loop), and a kill-switch.
 *
 * <p><b>Autonomy is opt-in.</b> Disabled unless {@code -Dintelligence.triage.enabled=true}; the
 * wiring layer must consult {@link #enabled()} before attaching.
 *
 * <h3>Never block the emitting thread</h3>
 * {@link EventLog} subscribers run synchronously on the emitting thread, which may hold
 * {@code ingestLock}. The subscriber only checks the event type, offers to a bounded queue (shedding
 * when full), and hands off to its own daemon virtual-thread executor — the mandatory pattern. All
 * Signal reconstruction, filtering and the investigation run happen on the executor.
 */
public final class TriageQueue implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TriageQueue.class);

    /** Opt-in kill-switch: triage is off unless this system property is explicitly {@code true}. */
    public static final String ENABLED_FLAG = "intelligence.triage.enabled";
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;
    /** How many recent correlationIds the dedupe window remembers (oldest evicted first). */
    private static final int DEDUPE_WINDOW = 512;
    private static final int DEFAULT_SINCE_MINUTES = 1440;

    private final Executor executor;
    private final ExecutorService ownedPool; // non-null only when this queue created the pool
    private final BlockingQueue<Event> queue;
    private final Consumer<Incident> investigate;
    private final AtomicLong dropped = new AtomicLong();
    private final Consumer<Event> subscriber = this::onEvent;

    /** Bounded LRU of correlationIds already dispatched — a re-fire of the same breach is skipped. */
    private final Set<String> seen = java.util.Collections.newSetFromMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > DEDUPE_WINDOW;
                }
            });

    private volatile EventLog attached;

    /** Whether triage autonomy is enabled (opt-in). The wiring layer gates on this before attaching. */
    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_FLAG);
    }

    /** Production queue: an owned daemon virtual-thread-per-task executor, default capacity. */
    public TriageQueue(Consumer<Incident> investigate) {
        this(investigate,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("inspecto-triage-run-", 0).factory()),
                DEFAULT_QUEUE_CAPACITY, true);
    }

    /** Test seam: inject an executor (e.g. {@code Runnable::run} for synchronous drain) + queue cap. */
    TriageQueue(Consumer<Incident> investigate, Executor executor, int queueCapacity) {
        this(investigate, executor, queueCapacity, false);
    }

    private TriageQueue(Consumer<Incident> investigate, Executor executor, int queueCapacity, boolean owned) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        this.investigate = java.util.Objects.requireNonNull(investigate, "investigate");
        this.executor = executor;
        this.ownedPool = owned ? (ExecutorService) executor : null;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    /** Register on the live Signal bus. */
    public void attach(EventLog eventLog) {
        this.attached = eventLog;
        eventLog.addSubscriber(subscriber);
    }

    /** Subscriber body — minimum work on the emitting thread: type check, offer, hand off. */
    private void onEvent(Event event) {
        if (event == null || !EventType.SIGNAL.equals(event.type())) return;
        if (!queue.offer(event)) {
            log.warn("triage queue full; dropped signal event (total dropped={})", dropped.incrementAndGet());
            return;
        }
        try {
            executor.execute(this::drainOne);
        } catch (RejectedExecutionException shuttingDown) {
            queue.poll(); // couldn't schedule (closing) — undo the enqueue
        }
    }

    /** Reconstruct, filter, dedupe and (when it clears) investigate one event — off the emit path. */
    private void drainOne() {
        Event e = queue.poll();
        if (e == null) return;
        try {
            Signal s = Signal.fromEvent(e);
            if (s.type() != null && s.type().startsWith("agent.")) return;        // never chase own telemetry
            if (s.severity().ordinal() < Severity.ERROR.ordinal()) return;         // error/critical floor
            String corr = dedupeKey(s);
            synchronized (seen) {
                if (!seen.add(corr)) return;                                       // already investigated
            }
            investigate.accept(toIncident(s));
        } catch (RuntimeException ex) {
            log.warn("triage failed to investigate a signal event: {}", ex.getMessage());
        }
    }

    /** The dedupe key — the correlation chain when present, else the signal's own id. */
    private static String dedupeKey(Signal s) {
        return s.correlationId() != null && !s.correlationId().isBlank() ? s.correlationId() : s.signalId();
    }

    /** Derive an {@link Incident} + the analysis-tool params the playbook can use from the Signal. */
    private static Incident toIncident(Signal s) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sinceMinutes", DEFAULT_SINCE_MINUTES);
        Ref subject = s.subject();
        if (subject != null && "pipeline".equals(subject.kind()) && subject.id() != null) {
            params.put("pipeline", subject.id());
        }
        Object expectation = s.payload() == null ? null : s.payload().get("expectation");
        if (expectation != null) {
            params.put("focusType", "expectation");
            params.put("focusId", String.valueOf(expectation));
        }
        return new Incident(dedupeKey(s), s.toMap(), params);
    }

    /** Count of events shed because the hand-off queue was full (diagnostics/tests). */
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        EventLog eventLog = attached;
        if (eventLog != null) eventLog.removeSubscriber(subscriber);
        if (ownedPool != null) ownedPool.shutdown();
    }
}
