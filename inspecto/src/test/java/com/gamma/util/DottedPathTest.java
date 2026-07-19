package com.gamma.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the shared dotted-path walker (event-signal-backbone-plan §4.4) — the one resolver
 *  {@code NotificationTemplate}, {@code WhenGuard} and {@code ParameterResolver}'s {@code $signal.} case
 *  all delegate to. */
class DottedPathTest {

    @Test
    void walksNestedMaps() {
        Map<String, Object> root = Map.of("payload", Map.of("stats", Map.of("errorRate", 0.42)));
        assertEquals(0.42, DottedPath.resolve(root, "payload.stats.errorRate"));
    }

    @Test
    void singleSegmentIsAFlatLookup() {
        Map<String, Object> root = Map.of("rowsOut", 1200);
        assertEquals(1200, DottedPath.resolve(root, "rowsOut"), "a 1-hop walk is identical to a flat .get()");
    }

    @Test
    void missingOrNonMapHopIsNull() {
        Map<String, Object> root = Map.of("a", Map.of("b", "leaf"));
        assertNull(DottedPath.resolve(root, "a.missing"));
        assertNull(DottedPath.resolve(root, "a.b.c"), "b is a String, not a Map — the walk stops");
        assertNull(DottedPath.resolve(null, "a.b"));
    }
}
