package com.gamma.intelligence.investigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * In-memory bounded ring of {@link Case}s (AGT-5 P1 slice D, plan §"Decisions" D2) — mirrors
 * {@code DiagnosisStore}'s shape (synchronized {@link ArrayDeque}, evict-oldest-on-overflow). Case
 * persistence + similarity recall is a later phase (plan §7); this is the deliberative layer's
 * first-cut memory, separate from the reflex layer's {@code DiagnosisStore}.
 */
public final class CaseStore {

    private static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Deque<Case> ring = new ArrayDeque<>();

    public CaseStore() {
        this(DEFAULT_CAPACITY);
    }

    CaseStore(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(Case c) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(c);
    }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Case> recent(int limit) {
        List<Case> copy = new ArrayList<>(ring);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    public synchronized Optional<Case> byId(String id) {
        return ring.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public synchronized int size() {
        return ring.size();
    }
}
