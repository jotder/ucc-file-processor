package com.gamma.notify;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A rolling-window cap on how many notifications sharing one {@code dedupeKey} may be raised — the
 * safeguard against accidental notification loops a flapping source could otherwise create (the dedup
 * in {@link NotificationService} only collapses identical <em>unread</em> alerts; this also bounds the
 * read-then-recur cycle). Default: at most {@value #DEFAULT_MAX_PER_HOUR} identical notifications per
 * hour. Cheap, in-memory, and time-injectable for tests.
 *
 * @since 4.4.0
 */
final class NotificationRateLimiter {

    static final int DEFAULT_MAX_PER_HOUR = 5;
    static final long ONE_HOUR_MS = 3_600_000L;

    private final int maxPerWindow;
    private final long windowMs;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    NotificationRateLimiter(int maxPerWindow, long windowMs) {
        this(maxPerWindow, windowMs, System::currentTimeMillis);
    }

    NotificationRateLimiter(int maxPerWindow, long windowMs, LongSupplier clock) {
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.windowMs = Math.max(1, windowMs);
        this.clock = clock;
    }

    /**
     * Record an attempt for {@code dedupeKey} and report whether it is within the window cap. A blank key
     * is never limited. Synchronized — dispatches may arrive on several virtual-thread workers.
     */
    synchronized boolean allow(String dedupeKey) {
        if (dedupeKey == null || dedupeKey.isBlank()) return true;
        long now = clock.getAsLong();
        Deque<Long> window = hits.computeIfAbsent(dedupeKey, k -> new ArrayDeque<>());
        while (!window.isEmpty() && now - window.peekFirst() >= windowMs) window.pollFirst();
        if (window.size() >= maxPerWindow) return false;
        window.addLast(now);
        return true;
    }
}
