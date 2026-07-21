package com.gamma.ops.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link QueueStore} — the lean default (INC-4), mirroring {@link com.gamma.ops.InMemoryObjectStore}.
 * Queues are low-volume config, so a plain map keyed by id is ample; the round-robin cursors ride a second
 * map of monotonic counters (mod the member count at read time, so a shrunk queue never over-indexes).
 *
 * <p>Thread-safe: the maps are concurrent and the cursor advance is atomic.
 *
 * @since 4.9.0
 */
public final class InMemoryQueueStore implements QueueStore {

    private final Map<String, Queue> byId = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> cursors = new ConcurrentHashMap<>();

    @Override
    public Queue put(Queue queue) {
        byId.put(queue.id(), queue);
        return queue;
    }

    @Override
    public Optional<Queue> get(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id.trim()));
    }

    @Override
    public List<Queue> all() {
        return new ArrayList<>(byId.values());
    }

    @Override
    public int advanceCursor(String queueId, int size) {
        if (size <= 0) throw new IllegalArgumentException("queue '" + queueId + "' has no members to route to");
        int n = cursors.computeIfAbsent(queueId, k -> new AtomicInteger()).getAndIncrement();
        return Math.floorMod(n, size);
    }
}
