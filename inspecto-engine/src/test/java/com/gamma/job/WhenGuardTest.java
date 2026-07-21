package com.gamma.job;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code when:} guard evaluation — flat {@code $signal.<field>} (backward-compatible) and nested
 *  {@code $signal.<path>} (event-signal-backbone-plan §4.4, via the shared {@code DottedPath} walker). */
class WhenGuardTest {

    @Test
    void blankGuardAlwaysRuns() {
        assertTrue(WhenGuard.eval(null, Map.of()));
        assertTrue(WhenGuard.eval("  ", Map.of()));
    }

    @Test
    void flatFieldStillResolvesExactlyAsBefore() {
        Map<String, Object> payload = Map.of("errorRate", 0.2);
        assertTrue(WhenGuard.eval("$signal.errorRate == 0.2", payload));
        assertFalse(WhenGuard.eval("$signal.errorRate == 0.5", payload));
    }

    @Test
    void nestedDottedPathResolves() {
        Map<String, Object> payload = Map.of("stats", Map.of("errorRate", 0.42));
        assertTrue(WhenGuard.eval("$signal.stats.errorRate == 0.42", payload));
        assertTrue(WhenGuard.eval("$signal.stats.errorRate > 0.1 && $signal.stats.errorRate < 1", payload));
    }

    @Test
    void missingNestedFieldIsFailClosed() {
        assertFalse(WhenGuard.eval("$signal.stats.missing == 1", Map.of("stats", Map.of())));
    }
}
