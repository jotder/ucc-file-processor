package com.gamma.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-name serialized execution — the one home for the {@code locks.computeIfAbsent +
 * lock/tryLock + try/finally unlock} mechanics previously hand-rolled by each service.
 * The work itself is injected; what differs per domain (audit rows, metrics, skip
 * semantics) stays with the caller as closures.
 *
 * <p>Two modes, matching the two service semantics:
 * <ul>
 *   <li>{@link #runExclusive} — <b>block</b> until the name's lock is free (an event and a
 *       scheduled run for the same job queue up rather than overlap);</li>
 *   <li>{@link #runExclusiveOrSkip} — <b>never wait</b>: if a previous run is still in
 *       flight, run the {@code onBusy} closure instead (e.g. record a {@code SKIPPED} run).</li>
 * </ul>
 * Different names always run in parallel.
 */
public final class LockingRunner {

    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Run {@code task} holding {@code name}'s lock, blocking until it is free. */
    public void runExclusive(String name, Runnable task) {
        ReentrantLock lock = locks.computeIfAbsent(name, k -> new ReentrantLock());
        lock.lock();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }

    /** Run {@code task} if {@code name}'s lock is free; otherwise run {@code onBusy} instead. */
    public void runExclusiveOrSkip(String name, Runnable task, Runnable onBusy) {
        ReentrantLock lock = locks.computeIfAbsent(name, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            onBusy.run();
            return;
        }
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}
