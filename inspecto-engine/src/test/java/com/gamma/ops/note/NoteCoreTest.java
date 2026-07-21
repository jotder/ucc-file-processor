package com.gamma.ops.note;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase-4-follow-up note value type + both stores: the comment/attachment factories (attachment
 * metadata rides the attribute bag), newest-first per-object reads with an optional kind filter, and a
 * DuckDB round-trip incl. the attachment attributes — mirroring {@code LinkCoreTest}/{@code DbObjectStoreTest}.
 */
class NoteCoreTest {

    @Test
    void factoriesValidateAndCarryAttachmentMetadata() {
        ObjectNote c = ObjectNote.comment("CASE-1", "alice", "looks bad");
        assertEquals(NoteKind.COMMENT, c.kind());
        assertEquals("looks bad", c.body());
        assertTrue(c.id().startsWith("NOTE-"));
        assertTrue(c.attributes().isEmpty());

        ObjectNote a = ObjectNote.attachment("CASE-1", "bob", "log.txt", "text/plain", "s3://x/log.txt", "tail");
        assertEquals(NoteKind.ATTACHMENT, a.kind());
        assertEquals("tail", a.body(), "caption becomes the body");
        assertEquals("log.txt", a.attributes().get("name"));
        assertEquals("text/plain", a.attributes().get("contentType"));
        assertEquals("s3://x/log.txt", a.attributes().get("uri"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectNote("", "o", NoteKind.COMMENT, "a", "b", null, 1), "blank id rejected");
    }

    @Test
    void inMemoryForObjectFiltersByKindNewestFirst() {
        InMemoryNoteStore store = new InMemoryNoteStore();
        store.add(new ObjectNote("N1", "O", NoteKind.COMMENT, "a", "first", null, 100));
        store.add(new ObjectNote("N2", "O", NoteKind.ATTACHMENT, "a", "", Map.of("name", "f"), 200));
        store.add(new ObjectNote("N3", "O", NoteKind.COMMENT, "a", "third", null, 300));
        store.add(new ObjectNote("NX", "OTHER", NoteKind.COMMENT, "a", "x", null, 400));

        assertEquals(3, store.forObject("O", null).size());
        assertEquals("N3", store.forObject("O", null).get(0).id(), "newest-first");
        assertEquals(2, store.forObject("O", NoteKind.COMMENT).size());
        assertEquals(1, store.forObject("O", NoteKind.ATTACHMENT).size());
        assertTrue(store.forObject("none", null).isEmpty());
    }

    @Test
    void dbNoteStoreRoundTrips() throws Exception {
        try (DbNoteStore store = DbNoteStore.open("jdbc:duckdb:", null, null)) {   // in-memory database
            store.add(ObjectNote.comment("O", "alice", "hello"));
            store.add(ObjectNote.attachment("O", "bob", "log.txt", "text/plain", "s3://x", "cap"));

            assertEquals(2, store.forObject("O", null).size());
            List<ObjectNote> atts = store.forObject("O", NoteKind.ATTACHMENT);
            assertEquals(1, atts.size());
            assertEquals("log.txt", atts.get(0).attributes().get("name"), "attachment metadata round-trips");
            assertEquals("cap", atts.get(0).body());
            assertEquals(1, store.forObject("O", NoteKind.COMMENT).size());
        }
    }
}
