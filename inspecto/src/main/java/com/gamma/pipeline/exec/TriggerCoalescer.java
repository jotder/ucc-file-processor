package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>T13 — event coalescing under the non-overlapping lock.</b> An event-triggered flow (§3.6) does
 * <em>not</em> spawn a run per event; an event marks the entry "work available" and the flow is admitted
 * <b>once</b> under the same non-overlapping guard as a timer tick (§3.5) — so a storm of 1,000
 * file-arrival events collapses to one admitted run that drains the pending set, not 1,000 overlapping
 * runs. <b>A flow never overlaps itself.</b>
 *
 * <p>{@link #signal} sets a pending flag and tries to become the single draining thread; whoever is
 * draining loops while pending is set, so events arriving <em>during</em> a run are folded into exactly
 * one follow-up run. The design is lost-wakeup-free: every signaller both sets {@code pending} and
 * attempts a drain, and a signaller that finds a drain in flight is guaranteed that the active drainer
 * (or a racing signaller) will observe its {@code pending} after the current run.
 *
 * <p>This is the in-process debounce; a {@code coalesce:} window (a time debounce) is layered by the
 * caller scheduling the {@link #signal} after the window. The work itself ({@code run}) is injected — in
 * production it is "admit one run under the {@code ingestLock} + concurrency + lag budget".
 */
@PublicApi(since = "4.3.0")
public final class TriggerCoalescer {

    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicLong runs = new AtomicLong(0);
    private final AtomicLong signals = new AtomicLong(0);

    /**
     * Record an event and admit a coalesced run. Returns immediately if a run is already draining (this
     * event is folded into it). {@code run} is invoked at least once after this call's signal and never
     * concurrently with itself.
     */
    public void signal(Runnable run) {
        signals.incrementAndGet();
        pending.set(true);
        while (true) {
            if (!draining.compareAndSet(false, true)) return;   // another thread is draining; it will see pending
            try {
                while (pending.compareAndSet(true, false)) {
                    runs.incrementAndGet();
                    run.run();
                }
            } finally {
                draining.set(false);
            }
            if (!pending.get()) return;                          // nothing arrived during/after the drain — done
            // a late signal slipped in after our last check but before we released — re-acquire and drain it
        }
    }

    /** Total events signalled (observability / tests). */
    public long signalCount() { return signals.get(); }

    /** Total runs actually admitted — {@code <= signalCount()} (the coalescing win). */
    public long runCount() { return runs.get(); }
}
