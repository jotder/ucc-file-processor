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
        return new Signal("id-" + ms, type, Instant.ofEpochMilli(ms), sev, Ref.of("job", "x"), null,
                corr, null, null, null, type, Map.of(), 1);
    }

    /** Three signals at t=1000/2000/3000 with distinct severities and two correlation chains. */
    private static InMemoryEventStore seeded() {
        InMemoryEventStore store = new InMemoryEventStore();
        store.append(sig("job.run.started",       1000, "corr1", Severity.INFO).toEvent());
        store.append(sig("job.run.failed",        2000, "corr1", Severity.CRITICAL).toEvent());
        store.append(sig("decision-rule.applied", 3000, "corr2", Severity.WARN).toEvent());
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
                types(Signals.query(seeded(), null, null, null, Severity.WARN, null, 100)),
                "WARN floor keeps WARN + CRITICAL");
        assertEquals(3, Signals.query(seeded(), null, null, null, Severity.INFO, null, 100).size(),
                "INFO floor keeps everything");
    }

    @Test
    void correlationIdAndLimit() {
        assertEquals(List.of("job.run.failed", "job.run.started"),
                types(Signals.query(seeded(), null, null, null, null, "corr1", 100)));
        assertEquals(1, Signals.query(seeded(), null, null, null, null, null, 1).size(), "limit caps the page");
    }

    private static Signal sourced(Ref source) {
        return new Signal("id", "job.run.started", Instant.ofEpochMilli(1000), Severity.INFO,
                source, null, "corr1", null, null, null, "m", Map.of(), 1);
    }

    @Test
    void sourceFilterMatchesKindOrCompactForm() {
        Signal jobX = sourced(new Ref("job", "nightly", "emits", "run:1"));
        assertTrue(Signals.matchesSource(jobX, null), "null filter ⇒ no filter");
        assertTrue(Signals.matchesSource(jobX, "  "), "blank filter ⇒ no filter");
        assertTrue(Signals.matchesSource(jobX, "job"), "matches by source kind");
        assertTrue(Signals.matchesSource(jobX, "JOB"), "kind match is case-insensitive");
        assertTrue(Signals.matchesSource(jobX, "job:nightly"), "matches by compact kind:id");
        assertFalse(Signals.matchesSource(jobX, "pipeline"), "a different kind ⇒ no match");
        assertFalse(Signals.matchesSource(jobX, "job:other"), "same kind, different id ⇒ no match");
        assertFalse(Signals.matchesSource(sourced(null), "job"), "a source-less signal is excluded by a source filter");
        assertTrue(Signals.matchesSource(sourced(null), null), "but not excluded when no filter is given");
    }

    @Test
    void matchesCombinesSourceWithTheOtherFilters() {
        Signal jobFail = new Signal("id", "job.run.failed", Instant.ofEpochMilli(2000), Severity.CRITICAL,
                Ref.of("job", "nightly"), null, "corr1", null, null, null, "m", Map.of(), 1);
        assertTrue(Signals.matches(jobFail, "job.run.*", Severity.WARN, "job", "corr1"));
        assertFalse(Signals.matches(jobFail, "job.run.*", Severity.WARN, "pipeline", "corr1"),
                "a non-matching source excludes even when type/severity/correlationId all match");
    }

    /** A causation-tree node builder: explicit id + causation + timestamp, everything else fixed. */
    private static Signal tnode(String id, String causationId, long ms) {
        return new Signal(id, "job.run.step", Instant.ofEpochMilli(ms), Severity.INFO,
                Ref.of("job", "x"), null, "corr1", causationId, null, null, "m", Map.of(), 1);
    }

    private static List<String> ids(List<Signals.SignalNode> nodes) {
        return nodes.stream().map(n -> n.signal().signalId()).toList();
    }

    @Test
    void assembleTreeNestsChildrenUnderTheirCause() {
        Signal a = tnode("A", null, 1000);
        Signal b = tnode("B", "A", 2000);
        Signal c = tnode("C", "B", 3000);
        List<Signals.SignalNode> roots = Signals.assembleTree(List.of(c, a, b));   // input order is irrelevant
        assertEquals(List.of("A"), ids(roots), "the only causeless signal is the single root");
        assertEquals(List.of("B"), ids(roots.get(0).children()), "B nests under its cause A");
        assertEquals(List.of("C"), ids(roots.get(0).children().get(0).children()), "C nests under its cause B");
    }

    @Test
    void assembleTreeBranchesAndSurfacesOrphansAsRoots() {
        Signal a = tnode("A", null, 1000);
        Signal b = tnode("B", "A", 2000);
        Signal c = tnode("C", "A", 3000);
        Signal orphan = tnode("D", "gone", 4000);          // cause points outside the set
        List<Signals.SignalNode> roots = Signals.assembleTree(List.of(a, b, c, orphan));
        assertEquals(List.of("A", "D"), ids(roots), "roots are oldest-first: real root A, then the orphan D");
        assertEquals(List.of("B", "C"), ids(roots.get(0).children()), "both children nest under A, oldest-first");
        assertTrue(roots.get(1).children().isEmpty(), "the orphan is a childless root, never dropped");
    }

    @Test
    void assembleTreeIsFiniteUnderAnInjectedCycle() {
        Signal x = tnode("X", "Y", 1000);
        Signal y = tnode("Y", "X", 2000);
        List<Signals.SignalNode> roots = Signals.assembleTree(List.of(x, y));   // must not loop
        assertEquals(List.of("X", "Y"), ids(roots), "a cycle is broken — both members surface as roots");
        assertTrue(roots.get(0).children().isEmpty() && roots.get(1).children().isEmpty());
    }

    private static List<String> signalIds(List<Signal> signals) {
        return signals.stream().map(Signal::signalId).toList();
    }

    @Test
    void causationOrderFlattensTheForestDepthFirstOldestFirst() {
        Signal a = tnode("A", null, 1000);
        Signal b = tnode("B", "A", 2000);
        Signal c = tnode("C", "A", 3000);
        Signal orphan = tnode("D", "gone", 4000);          // cause points outside the set → a root
        // pre-order flatten of assembleTree: root A, then A's children oldest-first (B, C), then the
        // orphan root D — nothing dropped, each signal exactly once. Input order is irrelevant.
        assertEquals(List.of("A", "B", "C", "D"),
                signalIds(Signals.causationOrder(List.of(c, orphan, a, b))));
    }

    @Test
    void causationOrderIsFiniteUnderAnInjectedCycle() {
        Signal x = tnode("X", "Y", 1000);
        Signal y = tnode("Y", "X", 2000);
        // Canonical cycle handling: assembleTree surfaces cycle members as roots, so the flatten lists
        // them at their oldest-first root position (never looping, never dropping) — this is the one
        // deliberate divergence from the retired flat impl, which appended cycle remnants at the end.
        assertEquals(List.of("X", "Y"), signalIds(Signals.causationOrder(List.of(y, x))));
    }

    /** S0 DoD: a nested payload round-trips through {@code toEvent()}/{@code fromEvent()} with zero
     *  JSON-in-attribute (the {@link Signal#payload()} stays a real, nested {@code Map<String,Object>}
     *  end to end), and the API view's {@code source} is a nested {@link Ref} object, not a flat string. */
    @Test
    void nestedPayloadRoundTripsAndSourceIsATypedRef() {
        Map<String, Object> nested = Map.of(
                "dataset", "orders",
                "stats", Map.of("rowsIn", 15200, "rowsOut", 15184));
        Signal original = new Signal(null, "job.dataset.produced", Instant.now(), Severity.INFO,
                new Ref("job", "nightly_recon", "emits", null), Ref.of("dataset", "orders"),
                "corr-1", "cause-1", "acme", Ref.of("agent", "recon-bot"),
                "dataset produced", nested, 1);

        Signal roundTripped = Signal.fromEvent(original.toEvent());

        assertEquals(nested, roundTripped.payload(), "payload survives the round trip as a real nested map");
        assertInstanceOf(Map.class, roundTripped.payload().get("stats"), "nested payload stays structured, never re-stringified");
        assertEquals(original.source(), roundTripped.source());
        assertEquals(original.subject(), roundTripped.subject());
        assertEquals(original.actor(), roundTripped.actor());
        assertEquals("cause-1", roundTripped.causationId());
        assertEquals("acme", roundTripped.space());

        Map<String, Object> api = roundTripped.toMap();
        assertInstanceOf(Map.class, api.get("source"), "/signals returns a Ref source, not a bare string");
        assertEquals("job", ((Map<?, ?>) api.get("source")).get("kind"));
        assertEquals("nightly_recon", ((Map<?, ?>) api.get("source")).get("id"));
        assertEquals("info", api.get("severity"), "severity is wire-lowercase per openapi-v1.json");
        assertEquals(nested, api.get("payload"), "the API payload is a real nested object, never a JSON string");
    }
}
