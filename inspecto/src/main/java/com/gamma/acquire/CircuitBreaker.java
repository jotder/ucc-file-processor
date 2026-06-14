package com.gamma.acquire;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-source circuit breaker for acquisition connectivity (Data Acquisition roadmap Phase F). When a source's
 * connector repeatedly fails to reach/list its endpoint, the breaker trips {@link State#OPEN} and the engine
 * <em>skips</em> that source for a cooldown window instead of hammering a dead endpoint every poll cycle. After
 * the cooldown a single {@link State#HALF_OPEN} trial is allowed; success closes the breaker, another failure
 * re-opens it.
 *
 * <p>State is process-wide and keyed by {@code source.id} on the {@link #shared()} singleton — the same
 * cross-cycle-state idiom as {@link StabilityGate#shared()} / {@link AcquisitionLedgers#shared()}, since each
 * static poll cycle is a fresh run. The clock is injectable so tests can advance cooldowns deterministically.
 *
 * <p>Thresholds/cooldowns are passed per call (from {@code source.circuit_breaker:}) rather than stored, so one
 * shared instance serves every pipeline without per-source configuration coupling.
 */
public final class CircuitBreaker {

    /** Breaker state for one source. */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private static final CircuitBreaker SHARED = new CircuitBreaker(System::currentTimeMillis);

    /** The process-wide breaker shared by the static poll path. */
    public static CircuitBreaker shared() {
        return SHARED;
    }

    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** Visible for tests; production code uses {@link #shared()}. */
    public CircuitBreaker(LongSupplier clock) {
        this.clock = clock;
    }

    private static final class Entry {
        State state = State.CLOSED;
        int consecutiveFailures;
        long openedAt;
    }

    /**
     * May the engine interact with {@code sourceId} this cycle? {@code true} when CLOSED, or when an OPEN breaker's
     * {@code cooldownMillis} has elapsed (it then transitions to HALF_OPEN to allow one trial). {@code false} while
     * a tripped breaker is still cooling down.
     */
    public synchronized boolean allow(String sourceId, long cooldownMillis) {
        Entry e = entries.computeIfAbsent(sourceId, k -> new Entry());
        if (e.state == State.OPEN && clock.getAsLong() - e.openedAt >= cooldownMillis) {
            e.state = State.HALF_OPEN;   // cooldown elapsed — let one trial through
        }
        return e.state != State.OPEN;
    }

    /** Record a successful interaction — closes the breaker and clears the failure count. */
    public synchronized void recordSuccess(String sourceId) {
        Entry e = entries.computeIfAbsent(sourceId, k -> new Entry());
        e.state = State.CLOSED;
        e.consecutiveFailures = 0;
    }

    /**
     * Record a connectivity failure. A failure during HALF_OPEN re-opens immediately; otherwise the breaker opens
     * once {@code failureThreshold} consecutive failures accumulate. Returns {@code true} iff this call tripped the
     * breaker OPEN (so the caller can emit the trip event exactly once).
     */
    public synchronized boolean recordFailure(String sourceId, int failureThreshold) {
        Entry e = entries.computeIfAbsent(sourceId, k -> new Entry());
        if (e.state == State.HALF_OPEN) {
            e.openedAt = clock.getAsLong();
            e.state = State.OPEN;
            return true;
        }
        e.consecutiveFailures++;
        if (e.state == State.CLOSED && e.consecutiveFailures >= Math.max(1, failureThreshold)) {
            e.openedAt = clock.getAsLong();
            e.state = State.OPEN;
            return true;
        }
        return false;
    }

    /** Current state for {@code sourceId} (CLOSED if never seen) — for observability/tests. */
    public synchronized State state(String sourceId) {
        Entry e = entries.get(sourceId);
        return e == null ? State.CLOSED : e.state;
    }

    /** Forget all breaker state — for test isolation. */
    public synchronized void reset() {
        entries.clear();
    }
}
