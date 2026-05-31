package com.gamma.agent.skill;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RepairLoop} — the generate → validate → repair primitive. Deterministic,
 * no model: the "generator" is a counter and the "validator" throws until a target round.
 */
class RepairLoopTest {

    @Test
    void succeedsFirstRoundWhenValid() {
        RepairLoop.Result<Integer> r = RepairLoop.run(3,
                feedback -> "42",
                Integer::parseInt);
        assertTrue(r.ok());
        assertEquals(42, r.value());
        assertEquals(1, r.rounds());
        assertFalse(r.repaired());
        assertTrue(r.errors().isEmpty());
    }

    @Test
    void repairsThenSucceedsAndFeedsErrorBack() {
        AtomicInteger round = new AtomicInteger();
        StringBuilder seenFeedback = new StringBuilder();
        RepairLoop.Result<Integer> r = RepairLoop.run(3,
                feedback -> {
                    if (feedback != null) seenFeedback.append(feedback);
                    // round 1 emits garbage, round 2 emits a number
                    return round.incrementAndGet() == 1 ? "not-a-number" : "7";
                },
                Integer::parseInt);
        assertTrue(r.ok());
        assertEquals(7, r.value());
        assertEquals(2, r.rounds());
        assertTrue(r.repaired(), "took a repair round");
        assertEquals(1, r.errors().size(), "one rejection recorded");
        assertTrue(seenFeedback.toString().contains("not-a-number"),
                "the verbatim rejected answer is fed back for self-correction");
    }

    @Test
    void stopsAtCapAndReturnsFailureWithErrors() {
        AtomicInteger calls = new AtomicInteger();
        RepairLoop.Result<Integer> r = RepairLoop.run(3,
                feedback -> { calls.incrementAndGet(); return "still-bad"; },
                Integer::parseInt);
        assertFalse(r.ok(), "never validated");
        assertNull(r.value());
        assertEquals(3, r.rounds(), "stopped exactly at the cap");
        assertEquals(3, calls.get(), "generated exactly maxRounds times — no runaway loop");
        assertEquals(3, r.errors().size());
        assertEquals("still-bad", r.lastRaw());
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class,
                () -> RepairLoop.run(0, f -> "x", s -> s));
    }
}
