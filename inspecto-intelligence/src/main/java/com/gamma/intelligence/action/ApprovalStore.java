package com.gamma.intelligence.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gamma.intelligence.store.DurableJsonlRing;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, durable ring of {@link Approval}s (AGT-5 P3). It holds both pending and already-decided
 * approvals so the inbox can show recent history; the blocking bridge to the eoiagent gate is
 * {@link AgentApprovals}' concern, this owns only storage and the guarded, once-only state transition.
 * Ring mechanics + JSON-lines durability come from {@link DurableJsonlRing}.
 */
public final class ApprovalStore extends DurableJsonlRing<Approval> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_CAPACITY = 256;
    private static final Codec<Approval> CODEC = new Codec<>() {
        @Override public Map<String, Object> toRecord(Approval a) { return a.toRecord(); }
        @Override public Approval fromRecord(Map<String, Object> m) { return Approval.fromRecord(m); }
    };

    public ApprovalStore() { this(DEFAULT_CAPACITY, null); }

    public ApprovalStore(Path file) { this(DEFAULT_CAPACITY, file); }

    ApprovalStore(int capacity) { this(capacity, null); }

    ApprovalStore(int capacity, Path file) { super(capacity, file, CODEC, "approval(s)"); }

    public void add(Approval a) { append(a); }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Approval> recent(int limit) { return recentSnapshot(limit); }

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
        persist();
        return found;
    }

    /** Mark a decision as delivered to a run (live unblock), so it can never double as a resume token. */
    public synchronized void markConsumed(String id) {
        byId(id).ifPresent(a -> { a.consume(); persist(); });
    }

    /**
     * One-shot resume-token lookup: the newest {@code APPROVED}/{@code DENIED} approval matching
     * {@code toolName} + {@code arguments} whose decision was never delivered to a run and was made
     * after {@code notBefore}. A match is consumed atomically (with this store's lock) so a decision
     * resumes exactly one run; a miss returns empty and the caller falls through to a fresh prompt.
     */
    public synchronized Optional<Approval> consumeDecided(String toolName, Map<String, Object> arguments,
                                                          Instant notBefore) {
        String wanted = canonical(arguments);
        List<Approval> newestFirst = recent(Integer.MAX_VALUE);
        for (Approval a : newestFirst) {
            if (a.consumed() || a.toolName() == null || !a.toolName().equals(toolName)) continue;
            if (a.status() != Approval.Status.APPROVED && a.status() != Approval.Status.DENIED) continue;
            if (a.decidedAt() == null || a.decidedAt().isBefore(notBefore)) continue;
            if (!canonical(a.arguments()).equals(wanted)) continue;
            a.consume();
            persist();
            return Optional.of(a);
        }
        return Optional.empty();
    }

    /** Canonical JSON with sorted keys — stable across a persistence round-trip (Integer/Long etc.). */
    private static String canonical(Map<String, Object> m) {
        try {
            return JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(m == null ? Map.of() : m);
        } catch (IOException e) {
            return String.valueOf(m);
        }
    }
}
