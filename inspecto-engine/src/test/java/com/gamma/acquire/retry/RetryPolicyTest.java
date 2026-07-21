package com.gamma.acquire.retry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Phase F — {@link RetryPolicy} backoff schedule + retry/succeed/exhaust behaviour, with an injected sleeper. */
class RetryPolicyTest {

    private final List<Long> slept = new ArrayList<>();
    private final RetryPolicy.Sleeper recorder = slept::add;

    @Test
    void noneRunsExactlyOnceAndNeverSleeps() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String r = RetryPolicy.NONE.execute(() -> { calls.incrementAndGet(); return "ok"; });
        assertEquals("ok", r);
        assertEquals(1, calls.get());
        assertEquals(1, RetryPolicy.NONE.attempts());
    }

    @Test
    void retriesUntilSuccessThenStops() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = RetryPolicy.forTest(5, RetryPolicy.Backoff.FIXED, 100L, 100L, recorder);
        String r = p.execute(() -> {
            if (calls.incrementAndGet() < 3) throw new RuntimeException("boom");
            return "third-time-lucky";
        });
        assertEquals("third-time-lucky", r);
        assertEquals(3, calls.get(), "failed twice, succeeded on the third attempt");
        assertEquals(List.of(100L, 100L), slept, "slept once before each of the two retries");
    }

    @Test
    void exhaustsRetriesAndRethrowsTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        RetryPolicy p = RetryPolicy.forTest(2, RetryPolicy.Backoff.FIXED, 10L, 10L, recorder);
        Exception ex = assertThrows(Exception.class, () -> p.execute(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("attempt-" + calls.get());
        }));
        assertEquals(3, calls.get(), "1 initial + 2 retries");
        assertEquals("attempt-3", ex.getMessage(), "the final failure is surfaced");
        assertEquals(2, slept.size(), "two backoff sleeps between three attempts");
    }

    @Test
    void exponentialBackoffDoublesAndClampsToMax() {
        RetryPolicy p = RetryPolicy.forTest(10, RetryPolicy.Backoff.EXPONENTIAL, 100L, 500L, recorder);
        assertEquals(100L, p.baseDelayMillis(0));
        assertEquals(200L, p.baseDelayMillis(1));
        assertEquals(400L, p.baseDelayMillis(2));
        assertEquals(500L, p.baseDelayMillis(3), "clamped at max_delay");
        assertEquals(500L, p.baseDelayMillis(20), "saturates without overflowing");
    }

    @Test
    void linearBackoffGrowsLinearly() {
        RetryPolicy p = RetryPolicy.forTest(10, RetryPolicy.Backoff.LINEAR, 50L, 10_000L, recorder);
        assertEquals(50L, p.baseDelayMillis(0));
        assertEquals(100L, p.baseDelayMillis(1));
        assertEquals(150L, p.baseDelayMillis(2));
    }
}
