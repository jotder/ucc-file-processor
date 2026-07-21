package com.gamma.intelligence.policy;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the rolling-window rate budget (AGT-5 P4). The clock is injected, so window rollover
 * is exercised deterministically without sleeping.
 */
class ActionBudgetTest {

    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long DAY = Duration.ofDays(1).toMillis();

    /** A hand-cranked clock so the trailing-window maths is deterministic. */
    private static final class Clock implements java.util.function.LongSupplier {
        long now = 1_000_000_000L;
        @Override public long getAsLong() { return now; }
        void advance(long ms) { now += ms; }
    }

    @Test
    void consumesUpToTheHourlyCapThenRefuses() {
        ActionBudget b = new ActionBudget(new Clock());
        assertTrue(b.tryConsume("run", 3, 100));
        assertTrue(b.tryConsume("run", 3, 100));
        assertTrue(b.tryConsume("run", 3, 100));
        assertFalse(b.tryConsume("run", 3, 100), "4th within the hour is over the hourly cap");
        assertFalse(b.allows("run", 3, 100), "allows() agrees without recording");
    }

    @Test
    void hourlyWindowRollsButDailyCapStillBinds() {
        Clock c = new Clock();
        ActionBudget b = new ActionBudget(c);
        // Spend the hourly cap of 2, twice across two hours → 4 total, under the daily cap of 5.
        assertTrue(b.tryConsume("a", 2, 5));
        assertTrue(b.tryConsume("a", 2, 5));
        assertFalse(b.tryConsume("a", 2, 5), "hourly cap hit");
        c.advance(HOUR + 1);
        assertTrue(b.tryConsume("a", 2, 5), "next hour: hourly window cleared");
        assertTrue(b.tryConsume("a", 2, 5));
        // 4 spent today; a 5th in a fresh hour is allowed, a 6th is over the daily cap.
        c.advance(HOUR + 1);
        assertTrue(b.tryConsume("a", 2, 5), "5th of the day, fresh hour");
        assertFalse(b.tryConsume("a", 2, 5), "6th exceeds the daily cap even in a fresh hour");
    }

    @Test
    void dayWindowFullyResetsAfterADay() {
        Clock c = new Clock();
        ActionBudget b = new ActionBudget(c);
        assertTrue(b.tryConsume("x", 1, 1));
        assertFalse(b.tryConsume("x", 1, 1), "daily cap of 1 hit");
        c.advance(DAY + 1);
        assertTrue(b.tryConsume("x", 1, 1), "a day later the window is empty again");
    }

    @Test
    void nonPositiveCapMeansUnlimitedOnThatWindow() {
        ActionBudget b = new ActionBudget(new Clock());
        for (int i = 0; i < 50; i++) {
            assertTrue(b.tryConsume("bulk", 0, 0), "cap <= 0 ⇒ no limit");
        }
        assertEquals(Integer.MAX_VALUE, b.remainingToday("bulk", 0), "unlimited day ⇒ MAX_VALUE");
    }

    @Test
    void remainingTodayCountsDownFromTheDailyCap() {
        ActionBudget b = new ActionBudget(new Clock());
        assertEquals(5, b.remainingToday("y", 5));
        b.tryConsume("y", 10, 5);
        b.tryConsume("y", 10, 5);
        assertEquals(3, b.remainingToday("y", 5));
    }

    @Test
    void classesAreBudgetedIndependently() {
        ActionBudget b = new ActionBudget(new Clock());
        assertTrue(b.tryConsume("classA", 1, 1));
        assertFalse(b.tryConsume("classA", 1, 1));
        assertTrue(b.tryConsume("classB", 1, 1), "a different action class has its own window");
    }
}
