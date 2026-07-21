package com.gamma.intelligence.investigation;

import com.gamma.intelligence.investigation.Feedback.Rating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AGT-5 P5: durable Case-feedback storage + rating parsing. */
class FeedbackStoreTest {

    private static Feedback fb(String id, String caseId, Rating r) {
        return new Feedback(id, caseId, r, "note-" + id, "alice", Instant.now());
    }

    @Test
    void addRecentByCaseAndById() {
        FeedbackStore store = new FeedbackStore();
        store.add(fb("f1", "case-1", Rating.HELPFUL));
        store.add(fb("f2", "case-1", Rating.NOT_HELPFUL));
        store.add(fb("f3", "case-2", Rating.HELPFUL));

        assertEquals(3, store.size());
        assertEquals("f3", store.recent(10).get(0).id()); // newest-first
        List<Feedback> forCase1 = store.byCaseId("case-1");
        assertEquals(2, forCase1.size());
        assertEquals("f2", forCase1.get(0).id());          // newest-first within a case
        assertEquals(Rating.HELPFUL, store.byId("f1").orElseThrow().rating());
        assertTrue(store.byId("nope").isEmpty());
        assertTrue(store.byCaseId("case-x").isEmpty());
    }

    @Test
    void boundedEvictsOldest() {
        FeedbackStore store = new FeedbackStore(2);
        store.add(fb("a", "c", Rating.HELPFUL));
        store.add(fb("b", "c", Rating.HELPFUL));
        store.add(fb("c", "c", Rating.HELPFUL));
        assertEquals(2, store.size());
        assertTrue(store.byId("a").isEmpty()); // oldest evicted
    }

    @Test
    void durableAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("agent").resolve("feedback.jsonl");
        FeedbackStore first = new FeedbackStore(file);
        first.add(fb("f1", "case-1", Rating.HELPFUL));
        first.add(fb("f2", "case-1", Rating.NOT_HELPFUL));

        FeedbackStore reloaded = new FeedbackStore(file);
        assertEquals(2, reloaded.size());
        assertEquals(Rating.NOT_HELPFUL, reloaded.byId("f2").orElseThrow().rating());
        assertEquals("note-f1", reloaded.byId("f1").orElseThrow().note());
    }

    @Test
    void parseRatingAcceptsSynonymsAndRejectsGarbage() {
        assertEquals(Rating.HELPFUL, Feedback.parseRating("helpful"));
        assertEquals(Rating.HELPFUL, Feedback.parseRating("UP"));
        assertEquals(Rating.NOT_HELPFUL, Feedback.parseRating("not_helpful"));
        assertEquals(Rating.NOT_HELPFUL, Feedback.parseRating("thumbs_down"));
        assertNull(Feedback.parseRating("banana"));
        assertNull(Feedback.parseRating(null));
    }

    @Test
    void viewRoundTripsThroughRecord() {
        Feedback f = fb("f1", "case-1", Rating.HELPFUL);
        Feedback back = Feedback.fromRecord(f.toView());
        assertEquals(f.id(), back.id());
        assertEquals(f.caseId(), back.caseId());
        assertEquals(f.rating(), back.rating());
        assertEquals(f.note(), back.note());
        assertEquals(f.submittedBy(), back.submittedBy());
    }
}
