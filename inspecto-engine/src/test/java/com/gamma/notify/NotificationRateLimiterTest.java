package com.gamma.notify;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the rolling-window identical-notification cap. */
class NotificationRateLimiterTest {

    @Test
    void allowsUpToCapThenDenies() {
        long[] now = { 0 };
        NotificationRateLimiter rl = new NotificationRateLimiter(3, 1000, () -> now[0]);
        assertTrue(rl.allow("k"));
        assertTrue(rl.allow("k"));
        assertTrue(rl.allow("k"));
        assertFalse(rl.allow("k"), "4th within the window is denied");
    }

    @Test
    void windowSlidesAndAllowsAgain() {
        long[] now = { 0 };
        NotificationRateLimiter rl = new NotificationRateLimiter(2, 1000, () -> now[0]);
        assertTrue(rl.allow("k"));
        assertTrue(rl.allow("k"));
        assertFalse(rl.allow("k"));
        now[0] = 1000; // the earlier hits fall outside the window
        assertTrue(rl.allow("k"), "window elapsed → allowed again");
    }

    @Test
    void blankKeyIsNeverLimited() {
        NotificationRateLimiter rl = new NotificationRateLimiter(1, 1000, () -> 0L);
        assertTrue(rl.allow(null));
        assertTrue(rl.allow(""));
        assertTrue(rl.allow("   "));
    }

    @Test
    void keysAreIndependent() {
        long[] now = { 0 };
        NotificationRateLimiter rl = new NotificationRateLimiter(1, 1000, () -> now[0]);
        assertTrue(rl.allow("a"));
        assertFalse(rl.allow("a"));
        assertTrue(rl.allow("b"), "a different dedupe key has its own budget");
    }
}
