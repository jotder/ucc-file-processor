package com.gamma.intelligence.action;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * In-memory bounded ring of {@link Approval}s (AGT-5 P3) — mirrors {@code CaseStore}'s shape
 * (synchronized {@link ArrayDeque}, evict-oldest-on-overflow). It holds both pending and already-
 * decided approvals so the inbox can show recent history; the blocking bridge to the eoiagent gate is
 * {@link AgentApprovals}' concern, this owns only storage and the guarded, once-only state transition.
 */
public final class ApprovalStore {

    private static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Deque<Approval> ring = new ArrayDeque<>();

    public ApprovalStore() { this(DEFAULT_CAPACITY); }

    ApprovalStore(int capacity) { this.capacity = capacity; }

    public synchronized void add(Approval a) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(a);
    }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Approval> recent(int limit) {
        List<Approval> copy = new ArrayList<>(ring);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    public synchronized Optional<Approval> byId(String id) {
        return ring.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /**
     * Transition a {@code PENDING} approval to a terminal status exactly once. Returns the approval on
     * a successful transition, or empty when the id is unknown or already decided — so a double
     * decision (operator racing the gate timeout, or two operators) is a no-op the route maps to 404.
     */
    public synchronized Optional<Approval> transition(String id, Approval.Status terminal, String by, Instant at) {
        Optional<Approval> found = byId(id);
        if (found.isEmpty() || found.get().status() != Approval.Status.PENDING) {
            return Optional.empty();
        }
        found.get().decide(terminal, by, at);
        return found;
    }

    public synchronized int size() { return ring.size(); }
}
