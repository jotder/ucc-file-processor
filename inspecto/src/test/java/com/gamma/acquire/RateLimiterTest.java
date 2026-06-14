package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Phase F — {@link RateLimiter} token-bucket throttling with an injected nano clock + sleeper. */
class RateLimiterTest {

    private final AtomicLong nanos = new AtomicLong(0);
    private final List<Long> slept = new ArrayList<>();

    /** A sleeper that "advances time" so the refill loop makes progress without real waiting. */
    private final RateLimiter.Sleeper clockSleeper = ms -> {
        slept.add(ms);
        nanos.addAndGet(ms * 1_000_000L);
    };

    @Test
    void unlimitedReturnsImmediately() throws Exception {
        RateLimiter rl = RateLimiter.forTest(0, nanos::get, clockSleeper);
        assertFalse(rl.active());
        rl.acquire(10_000_000L);
        assertTrue(slept.isEmpty(), "an unlimited limiter never sleeps");
    }

    @Test
    void firstBurstIsFreeThenItThrottles() throws Exception {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);   // 1000 B/s, 1s burst
        rl.acquire(1000);                  // consumes the initial full bucket — no wait
        assertTrue(slept.isEmpty());

        rl.acquire(1000);                  // bucket empty ⇒ must wait ~1s for a refill
        assertEquals(1, slept.size());
        assertEquals(1000L, slept.get(0), "waited one second to earn 1000 bytes at 1000 B/s");
    }

    @Test
    void refillAccruesOverElapsedTime() throws Exception {
        RateLimiter rl = RateLimiter.forTest(1000, nanos::get, clockSleeper);
        rl.acquire(1000);                  // drain the initial bucket
        nanos.addAndGet(500_000_000L);     // 0.5s passes ⇒ 500 bytes refilled
        rl.acquire(500);                   // exactly affordable — no sleep
        assertTrue(slept.isEmpty(), "accrued enough tokens during the elapsed half-second");
    }
}
