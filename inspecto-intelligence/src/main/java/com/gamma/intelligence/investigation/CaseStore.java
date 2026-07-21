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
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded ring of {@link Case}s (AGT-5 P1 slice D, plan §"Decisions" D2). Mirrors {@code ApprovalStore}'s
 * durability idiom: with a {@code file} it is loaded at construction and rewritten (one JSON object per
 * line, ≤ capacity lines) on every {@link #add}, so the investigation corpus survives a restart — the
 * substrate for P5 <b>case-similarity recall</b> ({@link #similar}). Without a file it is in-memory only
 * (dev/tests), as it was through P1–P4. All persistence failures degrade to in-memory (log + continue).
 */
public final class CaseStore {

    private static final Logger log = LoggerFactory.getLogger(CaseStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Path file; // null → in-memory only
    private final Deque<Case> ring = new ArrayDeque<>();

    public CaseStore() { this(DEFAULT_CAPACITY, null); }

    public CaseStore(Path file) { this(DEFAULT_CAPACITY, file); }

    CaseStore(int capacity) { this(capacity, null); }

    CaseStore(int capacity, Path file) {
        this.capacity = capacity;
        this.file = file;
        load();
    }

    public synchronized void add(Case c) {
        if (ring.size() >= capacity) ring.removeFirst();
        ring.addLast(c);
        persist();
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

    /**
     * The top-{@code k} prior Cases most similar to {@code queryText} (see {@link Case#symptomText()}),
     * by {@link CaseSimilarity} token overlap, excluding {@code excludeId} (typically the query Case
     * itself). Only positive-scoring matches are returned; each entry is the Case's {@link Case#toView()}
     * plus a {@code similarity} score, newest-first as the tie-break — so an investigator sees the most
     * relevant, most recent prior work first.
     */
    public synchronized List<Map<String, Object>> similar(String queryText, int k, String excludeId) {
        var queryTokens = CaseSimilarity.tokens(queryText);
        if (queryTokens.isEmpty() || k <= 0) return List.of();
        record Scored(Case c, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Case c : ring) {
            if (excludeId != null && excludeId.equals(c.id())) continue;
            double s = CaseSimilarity.jaccard(queryTokens, CaseSimilarity.tokens(c.symptomText()));
            if (s > 0.0) scored.add(new Scored(c, s));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(sc -> sc.c().createdAt(), Comparator.reverseOrder()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Scored sc : scored.subList(0, Math.min(k, scored.size()))) {
            Map<String, Object> view = new LinkedHashMap<>(sc.c().toView());
            view.put("similarity", Math.round(sc.score() * 1000.0) / 1000.0); // 3-dp, stable JSON
            out.add(view);
        }
        return out;
    }

    public synchronized int size() { return ring.size(); }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> record = JSON.readValue(line, new TypeReference<Map<String, Object>>() {});
                if (ring.size() >= capacity) ring.removeFirst();
                ring.addLast(Case.fromRecord(record));
            }
            log.info("Loaded {} persisted case(s) from {}", ring.size(), file);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not load persisted cases from {}: {}", file, e.getMessage());
            ring.clear();
        }
    }

    private void persist() {
        if (file == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Case c : ring) sb.append(JSON.writeValueAsString(c.toRecord())).append('\n');
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not persist cases to {}: {}", file, e.getMessage());
        }
    }
}
