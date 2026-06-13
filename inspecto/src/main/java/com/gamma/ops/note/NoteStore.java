package com.gamma.ops.note;

import java.util.List;

/**
 * Persistence seam for {@link ObjectNote}s — the evidence/notes store of the Operational Intelligence
 * Platform (Phase 4 follow-up). Append-only, like {@link com.gamma.event.EventStore} and
 * {@link com.gamma.ops.link.LinkStore}: notes are immutable facts, so there is no {@code update}.
 * {@link InMemoryNoteStore} is the lean default; {@link DbNoteStore} is durable JDBC over the bundled
 * DuckDB (or a Postgres URL), selected by the same {@code -Dobjects.backend} deployment toggle.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #forObject(String, NoteKind)} returns an object's notes <b>newest-first</b>; a
 *       {@code null} {@code kind} means "all kinds".</li>
 *   <li>Implementations must be thread-safe.</li>
 * </ul>
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.6.0")
public interface NoteStore extends AutoCloseable {

    /** Append a note and return it. */
    ObjectNote add(ObjectNote note);

    /** An object's notes, newest-first; {@code kind} {@code null} returns every kind. */
    List<ObjectNote> forObject(String objectId, NoteKind kind);

    /** Release resources (e.g. the DuckDB connection). Idempotent; no-op for in-memory. */
    @Override
    default void close() {}
}
