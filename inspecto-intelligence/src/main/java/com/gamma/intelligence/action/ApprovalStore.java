package com.gamma.intelligence.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded ring of {@link Approval}s (AGT-5 P3) — mirrors {@code CaseStore}'s shape
 * (synchronized {@link ArrayDeque}, evict-oldest-on-overflow). It holds both pending and already-
 * decided approvals so the inbox can show recent history; the blocking bridge to the eoiagent gate is
 * {@link AgentApprovals}' concern, this owns only storage and the guarded, once-only state transition.
 *
 * <p>With a {@code file}, the ring is durable: loaded at construction and rewritten (one JSON object
 * per line, ≤ capacity lines) on every mutation, so pending approvals and undelivered operator
 * decisions survive a process restart — the substrate for the P3 resume-after-restart flow. All
 * persistence failures degrade to in-memory-only (log + continue); durability never blocks the gate.
 */
public final class ApprovalStore {

    private static final Logger log = LoggerFactory.getLogger(ApprovalStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Path file; // null → in-memory only
    private final Deque<Approval> ring = new ArrayDeque<>();

    public ApprovalStore() { this(DEFAULT_CAPACITY, null); }

    public ApprovalStore(Path file) { this(DEFAULT_CAPACITY, file); }

    ApprovalStore(int capacity) { this(capacity, null); }

    ApprovalStore(int capacity, Path file) {
        this.capacity = capacity;
        this.file = file;
        load();
    }

    public synchronized void add(Approval a) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(a);
        persist();
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

    public synchronized int size() { return ring.size(); }

    /** Canonical JSON with sorted keys — stable across a persistence round-trip (Integer/Long etc.). */
    private static String canonical(Map<String, Object> m) {
        try {
            return JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(m == null ? Map.of() : m);
        } catch (IOException e) {
            return String.valueOf(m);
        }
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> record = JSON.readValue(line, new TypeReference<Map<String, Object>>() {});
                if (ring.size() >= capacity) ring.removeFirst();
                ring.addLast(Approval.fromRecord(record));
            }
            log.info("Loaded {} persisted approval(s) from {}", ring.size(), file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load persisted approvals from {}: {}", file, e.getMessage());
            ring.clear();
        }
    }

    private void persist() {
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Approval a : ring) {
                sb.append(JSON.writeValueAsString(a.toRecord())).append('\n');
            }
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist approvals to {}: {}", file, e.getMessage());
        }
    }
}
