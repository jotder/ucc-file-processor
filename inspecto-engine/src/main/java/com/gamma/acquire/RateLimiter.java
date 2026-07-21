package com.gamma.acquire;

import java.util.function.LongSupplier;

/**
 * A simple thread-safe token-bucket rate limiter in <b>bytes per second</b> (Data Acquisition roadmap Phase F,
 * {@code source.fetch.rate_limit}). The engine calls {@link #acquire(long)} before fetching each remote file so
 * aggregate transfer across all parallel fetch workers is bounded; a single shared limiter throttles the whole
 * source.
 *
 * <p>The bucket refills continuously at the configured rate and holds at most one second's worth of bytes
 * (burst), so a short idle period lets a single large file proceed without an unbounded credit build-up. An
 * {@code acquire} for more than the burst capacity is satisfied by waiting proportionally — it never deadlocks on
 * an over-large request. Both the clock and the sleep are injectable for deterministic tests; a non-positive rate
 * means <em>unlimited</em> and every call returns immediately.
 */
public final class RateLimiter {

    /** Pluggable sleep so tests need not wait on a real clock. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final double bytesPerSec;
    private final double capacity;     // max tokens held (1s burst)
    private final LongSupplier nanoClock;
    private final Sleeper sleeper;

    private double tokens;
    private long lastNanos;

    private RateLimiter(long bytesPerSec, LongSupplier nanoClock, Sleeper sleeper) {
        this.bytesPerSec = Math.max(0L, bytesPerSec);
        this.capacity = this.bytesPerSec;          // burst = one second of transfer
        this.nanoClock = nanoClock;
        this.sleeper = sleeper;
        this.tokens = this.capacity;               // start full so the first fetch isn't penalised
        this.lastNanos = nanoClock.getAsLong();
    }

    /** A real-clock limiter at {@code bytesPerSec}; {@code <= 0} ⇒ unlimited. */
    public static RateLimiter perSecond(long bytesPerSec) {
        return new RateLimiter(bytesPerSec, System::nanoTime, Thread::sleep);
    }

    /** Test seam: injected nanosecond clock + sleeper. */
    public static RateLimiter forTest(long bytesPerSec, LongSupplier nanoClock, Sleeper sleeper) {
        return new RateLimiter(bytesPerSec, nanoClock, sleeper);
    }

    /** Whether this limiter actually throttles (vs. unlimited). */
    public boolean active() {
        return bytesPerSec > 0;
    }

    /**
     * Block until {@code bytes} of transfer budget are available, then consume them. Unlimited limiters return at
     * once. A request larger than the burst capacity drains the bucket and waits for the remainder, so very large
     * files are throttled without being rejected.
     */
    public void acquire(long bytes) throws InterruptedException {
        if (bytesPerSec <= 0 || bytes <= 0) return;
        synchronized (this) {
            while (true) {
                refill();
                if (tokens >= bytes) {
                    tokens -= bytes;
                    return;
                }
                double deficit = bytes - tokens;
                long waitMillis = (long) Math.ceil(deficit / bytesPerSec * 1000.0);
                if (waitMillis <= 0) waitMillis = 1;
                sleeper.sleep(waitMillis);
            }
        }
    }

    private void refill() {
        long now = nanoClock.getAsLong();
        double elapsedSec = (now - lastNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(capacity, tokens + elapsedSec * bytesPerSec);
            lastNanos = now;
        }
    }
}
