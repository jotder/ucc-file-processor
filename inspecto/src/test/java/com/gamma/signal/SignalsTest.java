package com.gamma.signal;

import com.gamma.event.InMemoryEventStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Signals#query} filter logic over an {@link InMemoryEventStore} — the dotted-type glob, the
 * inclusive since/until time bounds, the severity floor (mapped onto the event-level ladder),
 * correlationId, and the page limit. Signals persist and reconstruct through {@link Signal#toEvent()} /
 * {@link Signal#fromEvent}, so this also proves that round-trip preserves what the filters read.
 */
class SignalsTest {

    private static Signal sig(String type, long ms, String corr, Severity sev) {
        return new Signal("id-" + ms, type, Instant.ofEpochMilli(ms), "job:x", corr, sev, Map.of());
    }

    /** Three signals at t=1000/2000/3000 with distinct severities and two correlation chains. */
    private static InMemoryEventStore seeded() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.append(sig("job.run.started",       1000, "corr1", Severity.INFO).toEvent());
        store.append(sig("job.run.failed",        2000, "corr1", Severity.CRITICAL).toEvent());
        store.append(sig("decision-rule.applied", 3000, "corr2", Severity.WARNING).toEvent());
        return store;
    }

    private static List<String> types(List<Signal> s) { return s.stream().map(Signal::type).toList(); }

    @Test
    void returnsAllNewestFirstWhenUnfiltered() {
        assertEquals(List.of("decision-rule.applied", "job.run.failed", "job.run.started"),
                types(Signals.query(seeded(), null, null, null, null, null, 100)));
    }

    @Test
    void typeGlobAndExactMatch() {
        assertEquals(List.of("job.run.failed", "job.run.started"),
                types(Signals.query(seeded(), "job.run.*", null, null, null, null, 100)));
        assertEquals(List.of("job.run.failed"),
                types(Signals.query(seeded(), "job.run.failed", null, null, null, null, 100)));
    }

    @Test
    void sinceAndUntilAreInclusiveBounds() {
        assertEquals(List.of("decision-rule.applied", "job.run.failed"),
                types(Signals.query(seeded(), null, 2000L, null, null, null, 100)), "since keeps ts >= bound");
        assertEquals(List.of("job.run.failed", "job.run.started"),
                types(Signals.query(seeded(), null, null, 2000L, null, null, 100)), "until keeps ts <= bound");
        assertEquals(List.of("job.run.failed"),
                types(Signals.query(seeded(), null, 2000L, 2000L, null, null, 100)), "since+until is a window");
    }

    @Test
    void severityFloorMapsOntoTheLevelLadder() {
        assertEquals(List.of("job.run.failed"),
                types(Signals.query(seeded(), null, null, null, Severity.CRITICAL, null, 100)),
                "CRITICAL floor keeps only the ERROR-level signal");
        assertEquals(List.of("decision-rule.applied", "job.run.failed"),
                types(Signals.query(seeded(), null, null, null, Severity.WARNING, null, 100)),
                "WARNING floor keeps WARNING + CRITICAL");
        assertEquals(3, Signals.query(seeded(), null, null, null, Severity.INFO, null, 100).size(),
                "INFO floor keeps everything");
    }

    @Test
    void correlationIdAndLimit() {
        assertEquals(List.of("job.run.failed", "job.run.started"),
                types(Signals.query(seeded(), null, null, null, null, "corr1", 100)));
        assertEquals(1, Signals.query(seeded(), null, null, null, null, null, 1).size(), "limit caps the page");
    }
}
