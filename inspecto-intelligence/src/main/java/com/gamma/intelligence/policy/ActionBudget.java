package com.gamma.intelligence.policy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-action-class rolling-window rate budget (AGT-5 P4, L3). Each recorded autonomous execution is a
 * timestamp; {@link #remaining}/{@link #tryConsume} count how many fall inside the trailing hour and
 * day windows and compare against the class caps. A cap {@code <= 0} means "no limit on that window".
 *
 * <p>In-memory by design: a restart re-opens the window (a conservative failure — the process that was
 * spending is gone). The clock is injectable for tests. Access is synchronized; the timestamp deques
 * are pruned to the day window on every touch, so memory is bounded by the daily cap.
 */
public final class ActionBudget {

    private static final long HOUR_MS = Duration.ofHours(1).toMillis();
    private static final long DAY_MS = Duration.ofDays(1).toMillis();

    private final LongSupplier clock;
    private final Map<String, Deque<Long>> executions = new HashMap<>();

    public ActionBudget() { this(System::currentTimeMillis); }

    public ActionBudget(LongSupplier clock) { this.clock = clock; }

    /** Whether an execution is within both caps right now, without recording one. */
    public synchronized boolean allows(String actionClass, int maxPerHour, int maxPerDay) {
        long now = clock.getAsLong();
        Deque<Long> times = pruned(actionClass, now);
        return withinCaps(times, now, maxPerHour, maxPerDay);
    }

    /** Record an execution now iff it is within both caps. Returns true when consumed, false when over budget. */
    public synchronized boolean tryConsume(String actionClass, int maxPerHour, int maxPerDay) {
        long now = clock.getAsLong();
        Deque<Long> times = pruned(actionClass, now);
        if (!withinCaps(times, now, maxPerHour, maxPerDay)) return false;
        times.addLast(now);
        return true;
    }

    /** Executions still available in the tighter of the two windows (for the dashboard); {@code MAX_VALUE} = unlimited. */
    public synchronized int remainingToday(String actionClass, int maxPerDay) {
        if (maxPerDay <= 0) return Integer.MAX_VALUE;
        long now = clock.getAsLong();
        return Math.max(0, maxPerDay - countSince(pruned(actionClass, now), now - DAY_MS));
    }

    private boolean withinCaps(Deque<Long> times, long now, int maxPerHour, int maxPerDay) {
        if (maxPerHour > 0 && countSince(times, now - HOUR_MS) >= maxPerHour) return false;
        if (maxPerDay > 0 && countSince(times, now - DAY_MS) >= maxPerDay) return false;
        return true;
    }

    private Deque<Long> pruned(String actionClass, long now) {
        Deque<Long> times = executions.computeIfAbsent(actionClass, k -> new ArrayDeque<>());
        long cutoff = now - DAY_MS;
        while (!times.isEmpty() && times.peekFirst() < cutoff) times.removeFirst();
        return times;
    }

    private static int countSince(Deque<Long> times, long cutoff) {
        int n = 0;
        for (long t : times) if (t >= cutoff) n++;
        return n;
    }
}
