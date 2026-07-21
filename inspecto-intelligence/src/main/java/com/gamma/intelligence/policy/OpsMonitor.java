package com.gamma.intelligence.policy;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.intelligence.policy.AutonomyPolicyEngine.Verdict;
import com.gamma.signal.Ref;
import com.gamma.signal.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bounded-autonomy driver (AGT-5 P4, L3) — the {@code ops_monitor} loop. A live {@link EventLog}
 * subscriber that watches for a remediable operational failure and, <em>gated by the
 * {@link AutonomyPolicyEngine}</em>, either remediates it autonomously, shadow-logs what it would do, or
 * stands down. Where {@code TriageQueue} (L1) turns a failure into an <em>investigation</em>, this (L3)
 * turns a failure into a bounded <em>action</em>.
 *
 * <p>The pilot action class is {@code batch_rerun}: a {@code pipeline.batch.failed} Signal ⇒ replay
 * that batch via the audited {@code pipeline_rerun} path. Every decision — including denials and
 * shadow no-ops — is written to the {@link AutonomyLog} for the dashboard, and every executed action
 * additionally rides the control plane's append-only audit as {@code actor=agent:ops-monitor}.
 *
 * <h3>Safety</h3>
 * <ul>
 *   <li><b>Opt-in.</b> Off unless {@code -Dintelligence.opsmonitor.enabled=true}; the wiring layer
 *       consults {@link #enabled()} before attaching.</li>
 *   <li><b>Policy-bounded.</b> Nothing executes unless the operator has set the class to {@code AUTO}
 *       and there is budget; the class defaults to {@code OFF}, and the kill switch overrides all.</li>
 *   <li><b>Never chases its own telemetry</b> ({@code agent.*} types are skipped) and dedupes by
 *       batch id, so one failure drives at most one action.</li>
 *   <li><b>{@code ingestLock} rule.</b> The subscriber only type-checks + offers to a bounded queue on
 *       the emitting thread, then hands off to its own daemon virtual-thread executor.</li>
 * </ul>
 */
public final class OpsMonitor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpsMonitor.class);

    /** Opt-in kill-switch (process-level): the loop is off unless this system property is {@code true}. */
    public static final String ENABLED_FLAG = "intelligence.opsmonitor.enabled";
    /** The event-driven pilot action class — replay a failed batch. Keyed into the {@link AutonomyPolicy}. */
    public static final String ACTION_BATCH_RERUN = "batch_rerun";
    /** The state-watch pilot action class — acknowledge/triage an open alert. */
    public static final String ACTION_ALERT_TRIAGE = "alert_triage";
    /** The Signal type the batch_rerun pilot reacts to (event-driven). */
    public static final String TRIGGER_BATCH_FAILED = "pipeline.batch.failed";
    /** The audited actor of an autonomous action (→ {@code actor=agent:ops-monitor}). */
    public static final String ACTOR_SESSION = "ops-monitor";

    private static final int DEFAULT_QUEUE_CAPACITY = 1024;
    private static final int DEDUPE_WINDOW = 512;

    /** Remediates one triggering subject via the audited act path; returns a human outcome detail.
     *  Throws to signal failure (mapped to {@link ActionRecord.Status#FAILED}). */
    @FunctionalInterface
    public interface Remediator {
        String remediate(String actionClass, Map<String, Object> subject) throws Exception;
    }

    /** One remediable state observation from a {@link StateScanner}: an action class + its subject,
     *  plus a stable dedupe key so a poll never re-acts on the same subject within the window. */
    public record Finding(String actionClass, String dedupeKey, Map<String, Object> subject) {}

    /** Produces the current remediable findings on demand — the state-watch (poll-driven) counterpart
     *  to the event-driven Signal path. Read-only; must not itself mutate anything. */
    @FunctionalInterface
    public interface StateScanner {
        List<Finding> scan();
    }

    private final AutonomyPolicyEngine policy;
    private final AutonomyLog ledger;
    private final Remediator remediator;
    private final Executor executor;
    private final ExecutorService ownedPool;
    private final BlockingQueue<Event> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final java.util.function.Consumer<Event> subscriber = this::onEvent;

    private final Set<String> seen = java.util.Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > DEDUPE_WINDOW;
                }
            });

    private volatile EventLog attached;
    private volatile ScheduledExecutorService stateWatchPool;
    private volatile ScheduledFuture<?> stateWatchTask;

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_FLAG);
    }

    /** Production: an owned daemon virtual-thread executor. */
    public OpsMonitor(AutonomyPolicyEngine policy, AutonomyLog ledger, Remediator remediator) {
        this(policy, ledger, remediator,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("inspecto-ops-monitor-", 0).factory()),
                DEFAULT_QUEUE_CAPACITY, true);
    }

    /** Test seam: inject an executor (e.g. {@code Runnable::run} for synchronous drain) + queue cap. */
    OpsMonitor(AutonomyPolicyEngine policy, AutonomyLog ledger, Remediator remediator,
               Executor executor, int queueCapacity) {
        this(policy, ledger, remediator, executor, queueCapacity, false);
    }

    private OpsMonitor(AutonomyPolicyEngine policy, AutonomyLog ledger, Remediator remediator,
                       Executor executor, int queueCapacity, boolean owned) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.ledger = java.util.Objects.requireNonNull(ledger, "ledger");
        this.remediator = java.util.Objects.requireNonNull(remediator, "remediator");
        this.executor = executor;
        this.ownedPool = owned ? (ExecutorService) executor : null;
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
    }

    public void attach(EventLog eventLog) {
        this.attached = eventLog;
        eventLog.addSubscriber(subscriber);
    }

    /**
     * Start the periodic <b>state-watch</b> (the poll-driven complement to the event-driven Signal
     * path): every {@code intervalSeconds} the {@code scanner} is asked for the current remediable
     * findings, each of which is deduped and run through {@link #decideAndAct} exactly like a Signal
     * would be. A single daemon thread runs the poll; a scan or remediation failure is logged and never
     * halts the schedule. Idempotent per instance — one state-watch at a time.
     */
    public synchronized void attachStateWatch(StateScanner scanner, long intervalSeconds) {
        if (stateWatchTask != null || intervalSeconds <= 0) return;
        stateWatchPool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "inspecto-ops-monitor-statewatch");
            t.setDaemon(true);
            return t;
        });
        stateWatchTask = stateWatchPool.scheduleWithFixedDelay(
                () -> pollOnce(scanner), intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("ops_monitor state-watch enabled (every {}s)", intervalSeconds);
    }

    /** One scan pass: fetch findings, dedupe, and decide+act on each. Package-private for direct tests. */
    void pollOnce(StateScanner scanner) {
        List<Finding> findings;
        try {
            findings = scanner.scan();
        } catch (RuntimeException e) {
            log.warn("ops_monitor state-watch scan failed: {}", e.getMessage());
            return;
        }
        for (Finding f : findings) {
            if (f == null || f.actionClass() == null) continue;
            synchronized (seen) {
                if (!seen.add("watch:" + f.dedupeKey())) continue; // one action per subject per window
            }
            try {
                decideAndAct(f.actionClass(), f.subject());
            } catch (RuntimeException e) {
                log.warn("ops_monitor state-watch failed on {}: {}", f.dedupeKey(), e.getMessage());
            }
        }
    }

    /** Emitting-thread work: type-check, offer, hand off. Never runs policy/remediation inline. */
    private void onEvent(Event event) {
        if (event == null || !EventType.SIGNAL.equals(event.type())) return;
        if (!queue.offer(event)) {
            log.warn("ops_monitor queue full; dropped signal event (total dropped={})", dropped.incrementAndGet());
            return;
        }
        try {
            executor.execute(this::drainOne);
        } catch (RejectedExecutionException shuttingDown) {
            queue.poll();
        }
    }

    /** Reconstruct, filter, dedupe and (when it clears) decide+act on one event — off the emit path. */
    private void drainOne() {
        Event e = queue.poll();
        if (e == null) return;
        try {
            Signal s = Signal.fromEvent(e);
            if (s.type() != null && s.type().startsWith("agent.")) return;   // never chase own telemetry
            if (!TRIGGER_BATCH_FAILED.equals(s.type())) return;              // pilot trigger only
            String pipeline = s.subject() != null ? s.subject().id() : null;
            String batchId = s.correlationId();                             // BatchAuditWriter sets this
            if (pipeline == null || pipeline.isBlank() || batchId == null || batchId.isBlank()) return;
            synchronized (seen) {
                if (!seen.add(batchId)) return;                             // one action per failed batch
            }
            decideAndAct(ACTION_BATCH_RERUN, subject(pipeline, batchId));
        } catch (RuntimeException ex) {
            log.warn("ops_monitor failed to process a signal event: {}", ex.getMessage());
        }
    }

    /** The core L3 step: authorize → (deny|shadow|execute) → ledger. Package-private for direct tests. */
    ActionRecord decideAndAct(String actionClass, Map<String, Object> subject) {
        Verdict v = policy.authorize(actionClass);
        String id = UUID.randomUUID().toString();
        switch (v.outcome()) {
            case DENY: {
                ActionRecord r = record(id, actionClass, subject, v, ActionRecord.Status.SKIPPED,
                        "not acted: " + v.reason());
                log.info("ops_monitor: {} on {} DENIED — {}", actionClass, subject, v.reason());
                return r;
            }
            case SHADOW: {
                ActionRecord r = record(id, actionClass, subject, v, ActionRecord.Status.SHADOWED,
                        "would " + actionClass + " " + subject);
                log.info("ops_monitor[SHADOW]: would {} on {} (execution suppressed)", actionClass, subject);
                return r;
            }
            case ALLOW:
            default:
                try {
                    String detail = remediator.remediate(actionClass, subject);
                    ActionRecord r = record(id, actionClass, subject, v, ActionRecord.Status.SUCCEEDED,
                            detail == null ? "remediated " + subject : detail);
                    log.info("ops_monitor: {} on {} SUCCEEDED — {}", actionClass, subject, r.status());
                    return r;
                } catch (Exception ex) {
                    ActionRecord r = record(id, actionClass, subject, v, ActionRecord.Status.FAILED,
                            "remediation failed: " + ex.getMessage());
                    log.warn("ops_monitor: {} on {} FAILED — {}", actionClass, subject, ex.getMessage());
                    return r;
                }
        }
    }

    private ActionRecord record(String id, String actionClass, Map<String, Object> subject, Verdict v,
                                ActionRecord.Status status, String detail) {
        ActionRecord r = new ActionRecord(id, actionClass, subject, v.outcome().name(), v.reason(),
                status, detail, Instant.now());
        ledger.add(r);
        return r;
    }

    private static Map<String, Object> subject(String pipeline, String batchId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipeline", pipeline);
        m.put("batchId", batchId);
        return m;
    }

    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        EventLog eventLog = attached;
        if (eventLog != null) eventLog.removeSubscriber(subscriber);
        if (stateWatchTask != null) stateWatchTask.cancel(false);
        if (stateWatchPool != null) stateWatchPool.shutdownNow();
        if (ownedPool != null) ownedPool.shutdown();
    }
}
