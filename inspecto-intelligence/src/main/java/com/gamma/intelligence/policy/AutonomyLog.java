package com.gamma.intelligence.policy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A bounded, in-memory ring of {@link ActionRecord}s (AGT-5 P4) — the autonomy ledger the dashboard
 * reads. Mirrors {@code CaseStore}/{@code ApprovalStore}'s shape (synchronized {@link ArrayDeque},
 * evict-oldest-on-overflow). In-memory by design: the ledger is a live operational view, not a durable
 * audit trail — every autonomous action <em>also</em> hits the control plane's append-only
 * {@code AuditTrail} (as {@code actor=agent:ops-monitor}), which is the system of record.
 */
public final class AutonomyLog {

    private static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Deque<ActionRecord> ring = new ArrayDeque<>();

    public AutonomyLog() { this(DEFAULT_CAPACITY); }

    public AutonomyLog(int capacity) { this.capacity = capacity; }

    public synchronized void add(ActionRecord r) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(r);
    }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<ActionRecord> recent(int limit) {
        List<ActionRecord> copy = new ArrayList<>(ring);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    public synchronized Optional<ActionRecord> byId(String id) {
        return ring.stream().filter(r -> r.id().equals(id)).findFirst();
    }

    public synchronized int size() { return ring.size(); }
}
