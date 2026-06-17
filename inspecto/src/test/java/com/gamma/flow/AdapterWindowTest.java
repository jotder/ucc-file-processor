package com.gamma.flow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 — {@link AdapterWindow}: the adapter micro-batch flush policy (first of max_records / max_bytes /
 * max_age wins; a {@code <= 0} bound is "no limit on that axis").
 */
class AdapterWindowTest {

    @Test
    void parsesAllThreeBoundsFromConfig() {
        AdapterWindow w = AdapterWindow.of(FlowNode.of("a", "adapter",
                Map.of("max_records", 100, "max_bytes", 1_048_576, "max_age", "30s")));
        assertEquals(100, w.maxRecords());
        assertEquals(1_048_576, w.maxBytes());
        assertEquals(30_000L, w.maxAgeMs());
        assertFalse(w.unbounded());
    }

    @Test
    void flushesOnWhicheverBoundIsReachedFirst() {
        AdapterWindow w = new AdapterWindow(100, 1_000, 30_000);
        assertFalse(w.shouldFlush(50, 500, 10_000));   // none reached
        assertTrue(w.shouldFlush(100, 0, 0));          // records
        assertTrue(w.shouldFlush(0, 1_000, 0));        // bytes
        assertTrue(w.shouldFlush(0, 0, 30_000));       // age
    }

    @Test
    void unboundedWindowNeverFlushesOnItsOwn() {
        AdapterWindow w = new AdapterWindow(0, 0, 0);
        assertTrue(w.unbounded());
        assertFalse(w.shouldFlush(1_000_000, 1_000_000, 1_000_000));
    }
}
