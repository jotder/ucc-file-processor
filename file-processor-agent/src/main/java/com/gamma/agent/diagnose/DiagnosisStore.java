package com.gamma.agent.diagnose;

import com.gamma.assist.Diagnosis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A bounded, in-memory ring of the most recent failure {@link Diagnosis}es produced by the
 * {@link FailureReactor} (v3.7.0, M7). It is the backing store for the read-only
 * {@code GET /assist/diagnoses} route, reached through
 * {@link com.gamma.agent.UccAssistAgent#recentDiagnoses(int)}.
 *
 * <p>Bounded so a long-running process with many failures never grows without limit — when full,
 * the oldest diagnosis is dropped. Thread-safe: the reactor's worker thread(s) add while the
 * control-plane thread reads, so every access is guarded by the intrinsic lock.
 */
public final class DiagnosisStore {

    /** Default cap — generous enough for an operator to page through recent failures, bounded for memory. */
    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Deque<Diagnosis> ring = new ArrayDeque<>();

    public DiagnosisStore() {
        this(DEFAULT_CAPACITY);
    }

    public DiagnosisStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
    }

    /** Record a diagnosis, evicting the oldest when at capacity. */
    public synchronized void add(Diagnosis d) {
        if (d == null) return;
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(d);
    }

    /** The most recent diagnoses, newest first, capped at {@code limit} (0 or negative → empty). */
    public synchronized List<Diagnosis> recent(int limit) {
        if (limit <= 0) return List.of();
        List<Diagnosis> all = new ArrayList<>(ring);
        Collections.reverse(all);                       // newest first
        return List.copyOf(all.subList(0, Math.min(limit, all.size())));
    }

    /** Current count (diagnostics/tests). */
    public synchronized int size() {
        return ring.size();
    }
}
