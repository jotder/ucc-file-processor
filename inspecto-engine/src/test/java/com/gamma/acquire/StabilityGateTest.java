package com.gamma.acquire;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.gamma.event.EventLog;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-B readiness gate: stabilization is wall-clock driven and connector readiness short-circuits.
 *  Driven by an injected clock + probe so the policy is exercised deterministically without sleeping. */
class StabilityGateTest {

    /** A fake source whose readiness is fixed; the other SPI methods are never called by the gate. */
    private static final class FakeConnector implements CollectorConnector {
        private final Readiness readiness;
        FakeConnector(Readiness r) { this.readiness = r; }
        public String scheme() { return "fake"; }
        public EnumSet<Capability> capabilities() { return EnumSet.noneOf(Capability.class); }
        public List<RemoteFile> discover(DiscoveryContext ctx) { throw new UnsupportedOperationException(); }
        public Readiness readiness(RemoteFile f) { return readiness; }
        public InputStream open(RemoteFile f) { throw new UnsupportedOperationException(); }
        public Path fetchTo(RemoteFile f, Path dest) { throw new UnsupportedOperationException(); }
        public void post(RemoteFile f, PostAction a) { throw new UnsupportedOperationException(); }
    }

    private static RemoteFile file(String rel) {
        return new RemoteFile(rel, rel, RemoteFile.SIZE_UNKNOWN, null, null, null, Path.of(rel));
    }

    /** Mutable clock + a per-path stat map the test rewrites between "cycles". */
    private static final long[] NOW = {1_000L};
    private static StabilityGate gate(Map<String, StabilityGate.FileStat> stats) {
        return new StabilityGate(() -> NOW[0], rf -> stats.getOrDefault(rf.relativePath(), StabilityGate.FileStat.NONE));
    }

    @Test
    void growingFileIsHeldThenReleasedOnceQuiescent() throws Exception {
        var stats = new java.util.HashMap<String, StabilityGate.FileStat>();
        StabilityGate gate = gate(stats);
        CollectorConnector conn = new FakeConnector(CollectorConnector.Readiness.UNKNOWN);
        RemoteFile f = file("feed.dat");
        long window = 10_000, checks = 2;

        // cycle 1: first sighting at size 100, mtime=now → held
        NOW[0] = 1_000;
        stats.put("feed.dat", new StabilityGate.FileStat(true, 100, 1_000));
        var r1 = gate.filter("S", List.of(f), conn, window, (int) checks);
        assertEquals(List.of(f), r1.waiting());
        assertTrue(r1.ready().isEmpty());

        // cycle 2: still growing — size changed and just modified → held, streak reset
        NOW[0] = 2_000;
        stats.put("feed.dat", new StabilityGate.FileStat(true, 200, 2_000));
        assertEquals(List.of(f), gate.filter("S", List.of(f), conn, window, (int) checks).waiting());

        // cycle 3: write finished — same size, quiescent for the full window, second matching sighting → released
        NOW[0] = 12_000;
        stats.put("feed.dat", new StabilityGate.FileStat(true, 200, 2_000));
        var r3 = gate.filter("S", List.of(f), conn, window, (int) checks);
        assertEquals(List.of(f), r3.ready());
        assertEquals(List.of(f), r3.newlyStable(), "the waiting→ready transition is reported for FILE_STABLE");
        assertTrue(r3.waiting().isEmpty());
    }

    @Test
    void sizeChecksGateReleaseEvenWhenQuiescent() throws Exception {
        var stats = new java.util.HashMap<String, StabilityGate.FileStat>();
        StabilityGate gate = gate(stats);
        CollectorConnector conn = new FakeConnector(CollectorConnector.Readiness.UNKNOWN);
        RemoteFile f = file("q.dat");
        NOW[0] = 5_000;
        stats.put("q.dat", new StabilityGate.FileStat(true, 50, 500));   // mtime old ⇒ always quiescent (window 0)

        // first quiescent sighting is not enough with size_checks=2
        assertTrue(gate.filter("S", List.of(f), conn, 0, 2).ready().isEmpty());
        // a second identical sighting clears the bar
        assertEquals(List.of(f), gate.filter("S", List.of(f), conn, 0, 2).ready());
    }

    @Test
    void recentlyModifiedFileStaysHeldNoMatterHowOftenPolled() throws Exception {
        var stats = new java.util.HashMap<String, StabilityGate.FileStat>();
        StabilityGate gate = gate(stats);
        CollectorConnector conn = new FakeConnector(CollectorConnector.Readiness.UNKNOWN);
        RemoteFile f = file("hot.dat");
        NOW[0] = 100_000;
        stats.put("hot.dat", new StabilityGate.FileStat(true, 10, 100_000));   // modified "now"
        // Repeated polls (no time passing) must never release a file still being written — the
        // release is gated on wall-clock quiescence, not on how many times filter() ran.
        for (int i = 0; i < 5; i++)
            assertTrue(gate.filter("S", List.of(f), conn, 10_000, 1).ready().isEmpty(),
                    "an actively-written file is held under polling pressure");
    }

    @Test
    void connectorReadinessShortCircuitsWithoutProbing() throws Exception {
        // A probe that explodes proves the gate never stats a file the connector judged natively.
        StabilityGate gate = new StabilityGate(() -> NOW[0], rf -> { throw new AssertionError("probed!"); });
        RemoteFile f = file("native.dat");

        var ready = gate.filter("S", List.of(f), new FakeConnector(CollectorConnector.Readiness.READY), 10_000, 2);
        assertEquals(List.of(f), ready.ready());
        assertTrue(ready.newlyStable().isEmpty(), "a connector-native READY is not a gate transition");

        var held = gate.filter("S", List.of(f), new FakeConnector(CollectorConnector.Readiness.NOT_READY), 10_000, 2);
        assertEquals(List.of(f), held.waiting());
        assertTrue(held.ready().isEmpty());
    }

    @Test
    void vanishedFileIsDroppedFromBothLists() throws Exception {
        StabilityGate gate = gate(Map.of());   // every probe ⇒ FileStat.NONE
        RemoteFile f = file("gone.dat");
        var r = gate.filter("S", List.of(f), new FakeConnector(CollectorConnector.Readiness.UNKNOWN), 10_000, 1);
        assertTrue(r.ready().isEmpty());
        assertTrue(r.waiting().isEmpty(), "a file that disappeared between discover and gate is simply dropped");
    }

    @Test
    void distinctSourceIdsDoNotShareObservations() throws Exception {
        var stats = new java.util.HashMap<String, StabilityGate.FileStat>();
        StabilityGate gate = gate(stats);
        CollectorConnector conn = new FakeConnector(CollectorConnector.Readiness.UNKNOWN);
        RemoteFile f = file("same.dat");
        NOW[0] = 9_000;
        stats.put("same.dat", new StabilityGate.FileStat(true, 1, 0));   // quiescent (window 0)
        // size_checks=2: each source must independently accumulate two sightings — the first call for
        // source B must not inherit source A's streak.
        gate.filter("A", List.of(f), conn, 0, 2);
        assertTrue(gate.filter("B", List.of(f), conn, 0, 2).ready().isEmpty(), "B has only one sighting");
        assertEquals(List.of(f), gate.filter("B", List.of(f), conn, 0, 2).ready(), "B's second sighting releases it");
        assertEquals(List.of(f), gate.filter("A", List.of(f), conn, 0, 2).ready(), "A's second sighting releases it");
    }

    @Test
    void instantBackedListingMetadataIsUsedByTheDefaultProbe() throws Exception {
        // The default (shared) probe reuses listing size+mtime when present rather than stat'ing the disk.
        StabilityGate gate = StabilityGate.shared();
        gate.clear();
        long old = Instant.now().toEpochMilli() - 60_000;   // a minute ago ⇒ quiescent under any small window
        RemoteFile f = new RemoteFile("r.dat", "phaseB/r.dat", 123, Instant.ofEpochMilli(old), "etag", null,
                Path.of("does-not-exist.dat"));
        CollectorConnector conn = new FakeConnector(CollectorConnector.Readiness.UNKNOWN);
        gate.filter("META", List.of(f), conn, 1_000, 2);                         // first sighting
        assertEquals(List.of(f), gate.filter("META", List.of(f), conn, 1_000, 2).ready(),
                "listing metadata (size+mtime) drives stabilization with no disk stat");
        gate.clear();
    }

    @Test
    void sharedGateIsPerSpace() {
        try {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            StabilityGate a = StabilityGate.shared();
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            StabilityGate b = StabilityGate.shared();
            assertNotSame(a, b, "each space gets its own gate ⇒ sightings can't collide across spaces");
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            assertSame(a, StabilityGate.shared(), "same space resolves to the same gate");
        } finally {
            MDC.remove(EventLog.SPACE_MDC_KEY);
        }
    }
}
