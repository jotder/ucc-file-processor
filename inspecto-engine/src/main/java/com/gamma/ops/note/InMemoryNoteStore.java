package com.gamma.ops.note;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link NoteStore} — the lean default (mirrors {@link com.gamma.ops.link.InMemoryLinkStore}).
 * Append-only; reads return newest-first, filtered by object (and optionally kind). Guarded on the
 * instance monitor, so it is safe to share across threads.
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.6.0")
public final class InMemoryNoteStore implements NoteStore {

    /** Insertion-ordered; reads iterate in reverse for newest-first. Guarded by {@code this}. */
    private final List<ObjectNote> notes = new ArrayList<>();

    @Override
    public synchronized ObjectNote add(ObjectNote note) {
        notes.add(note);
        return note;
    }

    @Override
    public synchronized List<ObjectNote> forObject(String objectId, NoteKind kind) {
        List<ObjectNote> out = new ArrayList<>();
        for (int i = notes.size() - 1; i >= 0; i--) {
            ObjectNote n = notes.get(i);
            if (n.objectId().equals(objectId) && (kind == null || n.kind() == kind)) out.add(n);
        }
        return out;
    }
}
