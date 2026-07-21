package com.gamma.intelligence.investigation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A durable, bounded ring of {@link Feedback} on investigation Cases (AGT-5 P5). Mirrors
 * {@code ApprovalStore}'s durability idiom: with a {@code file} it is loaded at construction and
 * rewritten (one JSON object per line, ≤ capacity lines) on every append, so operator feedback
 * accrues across restarts — the corpus the learning tier aggregates. Without a file it is in-memory
 * only (dev/tests). All persistence failures degrade to in-memory (log + continue).
 *
 * <p>Unlike the ephemeral {@code CaseStore}, feedback outlives the Case it points at (a Case may be
 * evicted from its 256-deep ring); the {@code caseId} is the durable join key.
 */
public final class FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(FeedbackStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final Path file; // null → in-memory only
    private final Deque<Feedback> ring = new ArrayDeque<>();

    public FeedbackStore() { this(DEFAULT_CAPACITY, null); }

    public FeedbackStore(Path file) { this(DEFAULT_CAPACITY, file); }

    FeedbackStore(int capacity) { this(capacity, null); }

    FeedbackStore(int capacity, Path file) {
        this.capacity = capacity;
        this.file = file;
        load();
    }

    public synchronized void add(Feedback f) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(f);
        persist();
    }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Feedback> recent(int limit) {
        List<Feedback> copy = new ArrayList<>(ring);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    /** All feedback for one case, newest-first. */
    public synchronized List<Feedback> byCaseId(String caseId) {
        List<Feedback> out = new ArrayList<>();
        for (Feedback f : ring) if (f.caseId().equals(caseId)) out.add(f);
        Collections.reverse(out);
        return out;
    }

    public synchronized Optional<Feedback> byId(String id) {
        return ring.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    public synchronized int size() { return ring.size(); }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                var record = JSON.readValue(line, new TypeReference<java.util.Map<String, Object>>() {});
                if (ring.size() >= capacity) ring.removeFirst();
                ring.addLast(Feedback.fromRecord(record));
            }
            log.info("Loaded {} persisted case-feedback entr(ies) from {}", ring.size(), file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load persisted feedback from {}: {}", file, e.getMessage());
            ring.clear();
        }
    }

    private void persist() {
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Feedback f : ring) sb.append(JSON.writeValueAsString(f.toRecord())).append('\n');
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist feedback to {}: {}", file, e.getMessage());
        }
    }
}
