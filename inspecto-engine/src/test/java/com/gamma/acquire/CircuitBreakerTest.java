package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Phase F — {@link CircuitBreaker} trip / cooldown / half-open behaviour with an injected clock. */
class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);
    private final CircuitBreaker cb = new CircuitBreaker(now::get);

    @Test
    void closedAllowsAndTripsOnThreshold() {
        assertTrue(cb.allow("s", 1000));
        assertFalse(cb.recordFailure("s", 3));    // 1
        assertFalse(cb.recordFailure("s", 3));    // 2
        assertTrue(cb.recordFailure("s", 3), "third consecutive failure trips OPEN");
        assertEquals(CircuitBreaker.State.OPEN, cb.state("s"));
        assertFalse(cb.allow("s", 1000), "OPEN within cooldown ⇒ skip");
    }

    @Test
    void successResetsTheFailureCount() {
        cb.recordFailure("s", 3);
        cb.recordFailure("s", 3);
        cb.recordSuccess("s");
        assertFalse(cb.recordFailure("s", 3), "count was reset — one failure doesn't trip");
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("s"));
    }

    @Test
    void halfOpensAfterCooldownThenClosesOnSuccess() {
        cb.recordFailure("s", 1);                 // trips immediately (threshold 1)
        assertEquals(CircuitBreaker.State.OPEN, cb.state("s"));
        assertFalse(cb.allow("s", 1000));

        now.set(1000);                            // cooldown elapsed
        assertTrue(cb.allow("s", 1000), "half-opens for one trial");
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state("s"));
        cb.recordSuccess("s");
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("s"));
    }

    @Test
    void halfOpenFailureReopensImmediately() {
        cb.recordFailure("s", 1);
        now.set(1000);
        assertTrue(cb.allow("s", 1000));          // HALF_OPEN
        assertTrue(cb.recordFailure("s", 1), "a failed trial re-opens");
        assertEquals(CircuitBreaker.State.OPEN, cb.state("s"));
        assertFalse(cb.allow("s", 1000), "re-opened, still cooling down");
    }

    @Test
    void perSourceIsolation() {
        cb.recordFailure("a", 1);
        assertEquals(CircuitBreaker.State.OPEN, cb.state("a"));
        assertEquals(CircuitBreaker.State.CLOSED, cb.state("b"));
        assertTrue(cb.allow("b", 1000));
    }
}
