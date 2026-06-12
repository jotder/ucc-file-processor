package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedHistoryTest {

    @Test
    void newestFirstAndBounded() {
        BoundedHistory<Integer> h = new BoundedHistory<>(3);
        for (int i = 1; i <= 5; i++) h.add(i);
        assertEquals(List.of(5, 4, 3), h.all());            // newest first, oldest evicted
        assertEquals(5, h.latest().orElseThrow());
    }

    @Test
    void emptyHistoryHasNoLatest() {
        BoundedHistory<String> h = new BoundedHistory<>(10);
        assertTrue(h.all().isEmpty());
        assertTrue(h.latest().isEmpty());
    }

    @Test
    void allReturnsASnapshotNotALiveView() {
        BoundedHistory<String> h = new BoundedHistory<>(5);
        h.add("a");
        List<String> snapshot = h.all();
        h.add("b");
        assertEquals(List.of("a"), snapshot);
    }
}
