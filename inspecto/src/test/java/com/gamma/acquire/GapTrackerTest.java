package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-D per-process gap dedup: a persistent gap fires once; a filled-then-reopened gap fires again. */
class GapTrackerTest {

    @Test
    void firstSightingFiresAllThenPersistentGapsAreSuppressed() {
        GapTracker t = new GapTracker();
        assertEquals(List.of("a", "b"), t.newGaps("S", List.of("a", "b")));
        assertEquals(List.of(), t.newGaps("S", List.of("a", "b")), "same gaps next cycle ⇒ nothing new");
    }

    @Test
    void onlyNewlyMissingKeysFire() {
        GapTracker t = new GapTracker();
        t.newGaps("S", List.of("a"));
        assertEquals(List.of("b"), t.newGaps("S", List.of("a", "b")), "a already reported, only b is fresh");
    }

    @Test
    void filledGapIsForgottenAndRefiresIfItReopens() {
        GapTracker t = new GapTracker();
        t.newGaps("S", List.of("a"));
        assertEquals(List.of(), t.newGaps("S", List.of()), "gap filled ⇒ pruned, nothing to fire");
        assertEquals(List.of("a"), t.newGaps("S", List.of("a")), "reopened ⇒ a genuine new hole, fires again");
    }

    @Test
    void perSourceIsolation() {
        GapTracker t = new GapTracker();
        assertEquals(List.of("a"), t.newGaps("S1", List.of("a")));
        assertEquals(List.of("a"), t.newGaps("S2", List.of("a")), "S2 has its own memory");
    }

    @Test
    void resetClearsMemory() {
        GapTracker t = new GapTracker();
        t.newGaps("S", List.of("a"));
        t.reset("S");
        assertEquals(List.of("a"), t.newGaps("S", List.of("a")), "after reset the gap is fresh again");
    }
}
