package com.gamma.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, in-memory ring of recent {@link Event}s — newest-first, process lifetime. This is the
 * <b>lean default</b> store ({@code -Devents.backend=memory}) and also serves as the live-tail buffer
 * inside {@code ParquetEventStore}. Mirrors the bounded-ring pattern already used by
 * {@code com.gamma.alert.AlertService} (fired-alert ring) and the agent's diagnosis store.
 *
 * <p>When capacity is exceeded the oldest events are dropped — events are immutable facts, so "drop
 * the oldest" is the only retention policy an in-memory store can offer (durable retention is
 * {@code ParquetEventStore}'s job). Thread-safe: all access is synchronized on the deque (event
 * volume is modest and a {@code synchronized} block keeps append + paged query trivially correct).
 *
 * @since 4.2.0
 */
@com.gamma.api.PublicApi(since = "4.2.0")
public final class InMemoryEventStore implements EventStore {

    /** Default ring capacity — generous enough for a useful live tail, small enough to bound heap. */
    public static final int DEFAULT_CAPACITY = 8192;

    private final Deque<Event> ring = new ArrayDeque<>();
    private final int capacity;

    public InMemoryEventStore() {
        this(DEFAULT_CAPACITY);
    }

    public InMemoryEventStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    @Override
    public synchronized void append(Event event) {
        if (event == null) return;
        ring.addFirst(event);                       // newest-first
        while (ring.size() > capacity) ring.removeLast();
    }

    @Override
    public synchronized List<Event> query(EventQuery query) {
        List<Event> out = new ArrayList<>();
        int skipped = 0;
        for (Event e : ring) {                       // iterates newest-first
            if (!query.matches(e)) continue;
            if (skipped < query.offset()) { skipped++; continue; }
            out.add(e);
            if (out.size() >= query.limit()) break;
        }
        return out;
    }

    @Override
    public synchronized List<Event> recent(int limit) {
        int n = Math.max(0, limit);
        List<Event> out = new ArrayList<>(Math.min(n, ring.size()));
        for (Event e : ring) {
            if (out.size() >= n) break;
            out.add(e);
        }
        return out;
    }

    @Override
    public synchronized List<Event> page(int limit, Long afterTs, String afterId) {
        return ring.stream()
                .sorted(KEYSET_ORDER)   // ring is insertion-ordered; the keyset needs (ts, id) strictly
                .filter(e -> EventStore.afterKey(e, afterTs, afterId))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public synchronized long count() {
        return ring.size();
    }

    /** Current ring size (diagnostics/tests). */
    public synchronized int size() {
        return ring.size();
    }
}
