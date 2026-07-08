package com.gamma.ops.queue;

import java.util.List;
import java.util.Optional;

/**
 * The by-id registry of {@link Queue}s plus the persistent round-robin cursors (INC-4). In-memory by
 * default ({@link InMemoryQueueStore}), mirroring how {@link com.gamma.ops.link.LinkStore} /
 * {@link com.gamma.ops.note.NoteStore} started — a durable {@code Db} variant can drop in behind the
 * same interface. Queues are configuration (authored as {@code *_queue.toon}), so the lean default holds
 * them in memory and re-loads them from config at boot; only the round-robin cursor is genuine runtime state.
 *
 * @since 4.9.0
 */
public interface QueueStore {

    /** Register (create or replace) a queue; returns it. */
    Queue put(Queue queue);

    /** The queue with this id, or empty. */
    Optional<Queue> get(String id);

    /** Every registered queue, in insertion order. */
    List<Queue> all();

    /**
     * The next round-robin index for {@code queueId} in {@code [0, size)}, advancing the persistent cursor.
     * {@code size} must be &gt; 0 (the caller checks the queue has members first).
     */
    int advanceCursor(String queueId, int size);
}
