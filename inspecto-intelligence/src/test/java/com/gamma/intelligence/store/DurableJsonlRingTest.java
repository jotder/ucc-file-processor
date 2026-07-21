package com.gamma.intelligence.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the bounded/durable JSONL ring (M7) — the mechanic shared by ApprovalStore,
 * CaseStore, FeedbackStore, and RunbookRunStore. A tiny concrete String subclass exercises capacity
 * eviction, newest-first snapshots, durability across a reload, in-memory mode, and corrupt-file
 * tolerance in one place.
 */
class DurableJsonlRingTest {

    /** Minimal concrete ring over Strings, exposing the protected mutators for the test. */
    private static final class StringRing extends DurableJsonlRing<String> {
        StringRing(int capacity, Path file) {
            super(capacity, file, new Codec<String>() {
                @Override public Map<String, Object> toRecord(String s) { return Map.of("v", s); }
                @Override public String fromRecord(Map<String, Object> r) { return String.valueOf(r.get("v")); }
            }, "item(s)");
        }
        void add(String s) { append(s); }
        List<String> recent(int limit) { return recentSnapshot(limit); }
    }

    @Test
    void appendEvictsOldestAtCapacity() {
        StringRing ring = new StringRing(2, null);
        ring.add("a");
        ring.add("b");
        ring.add("c");
        assertEquals(2, ring.size(), "capacity is a hard bound");
        assertEquals(List.of("c", "b"), ring.recent(10), "newest-first; 'a' evicted");
    }

    @Test
    void recentSnapshotIsNewestFirstAndCapped() {
        StringRing ring = new StringRing(10, null);
        ring.add("one");
        ring.add("two");
        ring.add("three");
        assertEquals(List.of("three", "two"), ring.recent(2), "limit caps the snapshot");
    }

    @Test
    void nullFileIsInMemoryOnly(@TempDir Path dir) {
        StringRing ring = new StringRing(5, null);
        ring.add("x");
        assertEquals(1, ring.size());
        // A null path writes nothing — the temp dir stays empty.
        assertEquals(0L, dir.toFile().list().length);
    }

    @Test
    void contentsSurviveAReloadFromFile(@TempDir Path dir) {
        Path file = dir.resolve("ring.jsonl");
        StringRing first = new StringRing(5, file);
        first.add("a");
        first.add("b");
        first.add("c");
        assertTrue(Files.exists(file), "a configured file is written on mutation");

        StringRing reloaded = new StringRing(5, file);
        assertEquals(3, reloaded.size(), "durable across a new instance");
        assertEquals(List.of("c", "b", "a"), reloaded.recent(10));
    }

    @Test
    void reloadHonoursCapacityEviction(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ring.jsonl");
        StringRing first = new StringRing(10, file);
        for (int i = 0; i < 6; i++) first.add("v" + i);

        // A smaller ring over the same file keeps only the newest `capacity` lines.
        StringRing smaller = new StringRing(2, file);
        assertEquals(2, smaller.size());
        assertEquals(List.of("v5", "v4"), smaller.recent(10));
    }

    @Test
    void corruptFileDegradesToEmptyWithoutThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("ring.jsonl");
        Files.writeString(file, "{ this is not valid json\n");
        StringRing ring = new StringRing(5, file);   // must not throw
        assertEquals(0, ring.size(), "an unreadable line clears the ring rather than failing the caller");
    }
}
