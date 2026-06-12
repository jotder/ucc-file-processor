package com.gamma.etl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link IngestProgress}: the live per-pipeline "which file is ingesting now" snapshot. */
class IngestProgressTest {

    @Test
    void trackThenCurrentThenClear() {
        String pipe = "ipt_track_" + System.nanoTime();
        assertNull(IngestProgress.current(pipe), "nothing tracked yet");

        IngestProgress.track(pipe, "B1", "a.csv.gz", 1, 3);
        IngestProgress.Snapshot s = IngestProgress.current(pipe);
        assertNotNull(s);
        assertEquals("B1", s.batchId());
        assertEquals("a.csv.gz", s.file());
        assertEquals(1, s.index());
        assertEquals(3, s.total());
        assertFalse(s.startedAt().isBlank());

        IngestProgress.track(pipe, "B1", "b.csv.gz", 2, 3);   // later member replaces the snapshot
        assertEquals("b.csv.gz", IngestProgress.current(pipe).file());
        assertEquals(2, IngestProgress.current(pipe).index());

        IngestProgress.clear(pipe);
        assertNull(IngestProgress.current(pipe), "cleared when the batch finishes");
    }

    @Test
    void pipelinesAreIndependentAndNullsAreSafe() {
        String a = "ipt_a_" + System.nanoTime();
        String b = "ipt_b_" + System.nanoTime();
        IngestProgress.track(a, "BA", "a.csv", 1, 1);
        IngestProgress.track(b, "BB", "b.csv", 1, 1);
        assertEquals("a.csv", IngestProgress.current(a).file());
        assertEquals("b.csv", IngestProgress.current(b).file());
        IngestProgress.clear(a);
        assertNull(IngestProgress.current(a));
        assertNotNull(IngestProgress.current(b), "clearing one pipeline leaves the other");
        IngestProgress.clear(b);

        // Null/blank pipeline names are ignored, never thrown.
        IngestProgress.track(null, "B", "f", 1, 1);
        IngestProgress.track("  ", "B", "f", 1, 1);
        IngestProgress.clear(null);
        assertNull(IngestProgress.current(null));
    }
}
