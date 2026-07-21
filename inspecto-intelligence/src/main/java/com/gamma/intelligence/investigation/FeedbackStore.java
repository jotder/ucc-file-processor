package com.gamma.intelligence.investigation;

import com.gamma.intelligence.store.DurableJsonlRing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A durable, bounded ring of {@link Feedback} on investigation Cases (AGT-5 P5) — the corpus the learning
 * tier aggregates. Ring mechanics + JSON-lines durability come from {@link DurableJsonlRing}.
 *
 * <p>Unlike the ephemeral {@code CaseStore}, feedback outlives the Case it points at (a Case may be
 * evicted from its 256-deep ring); the {@code caseId} is the durable join key, so this ring is deeper.
 */
public final class FeedbackStore extends DurableJsonlRing<Feedback> {

    private static final int DEFAULT_CAPACITY = 1024;
    private static final Codec<Feedback> CODEC = new Codec<>() {
        @Override public Map<String, Object> toRecord(Feedback f) { return f.toRecord(); }
        @Override public Feedback fromRecord(Map<String, Object> m) { return Feedback.fromRecord(m); }
    };

    public FeedbackStore() { this(DEFAULT_CAPACITY, null); }

    public FeedbackStore(Path file) { this(DEFAULT_CAPACITY, file); }

    FeedbackStore(int capacity) { this(capacity, null); }

    FeedbackStore(int capacity, Path file) { super(capacity, file, CODEC, "case-feedback entr(ies)"); }

    public void add(Feedback f) { append(f); }

    /** Newest-first, capped at {@code limit}. */
    public synchronized List<Feedback> recent(int limit) { return recentSnapshot(limit); }

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
}
