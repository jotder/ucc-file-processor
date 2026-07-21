package com.gamma.intelligence.investigation;

import com.gamma.intelligence.store.DurableJsonlRing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded, durable ring of {@link Case}s (AGT-5 P1 slice D). The investigation corpus survives a restart
 * — the substrate for P5 <b>case-similarity recall</b> ({@link #similar}). Ring mechanics + JSON-lines
 * durability come from {@link DurableJsonlRing}.
 */
public final class CaseStore extends DurableJsonlRing<Case> {

    private static final int DEFAULT_CAPACITY = 256;
    private static final Codec<Case> CODEC = new Codec<>() {
        @Override public Map<String, Object> toRecord(Case c) { return c.toRecord(); }
        @Override public Case fromRecord(Map<String, Object> m) { return Case.fromRecord(m); }
    };

    public CaseStore() { this(DEFAULT_CAPACITY, null); }

    public CaseStore(Path file) { this(DEFAULT_CAPACITY, file); }

    CaseStore(int capacity) { this(capacity, null); }

    CaseStore(int capacity, Path file) { super(capacity, file, CODEC, "case(s)"); }

    public void add(Case c) { append(c); }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Case> recent(int limit) { return recentSnapshot(limit); }

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
}
