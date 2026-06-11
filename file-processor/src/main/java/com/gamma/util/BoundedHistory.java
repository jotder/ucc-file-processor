package com.gamma.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A thread-safe, size-bounded, newest-first history — the in-memory "recent runs" buffer
 * the services keep beside their durable CSV ledgers. Replaces the hand-rolled
 * {@code Deque + synchronized} blocks previously duplicated per service.
 *
 * @param <T> the entry type (e.g. a run record)
 */
public final class BoundedHistory<T> {

    private final Deque<T> items = new ArrayDeque<>();
    private final int max;

    /** @param max entries retained; adding beyond it evicts the oldest */
    public BoundedHistory(int max) {
        this.max = max;
    }

    /** Prepend {@code item} (newest first), evicting the oldest past the bound. */
    public synchronized void add(T item) {
        items.addFirst(item);
        while (items.size() > max) items.removeLast();
    }

    /** A snapshot, newest first. */
    public synchronized List<T> all() {
        return new ArrayList<>(items);
    }

    /** The most recent entry, if any. */
    public synchronized Optional<T> latest() {
        return Optional.ofNullable(items.peekFirst());
    }
}
