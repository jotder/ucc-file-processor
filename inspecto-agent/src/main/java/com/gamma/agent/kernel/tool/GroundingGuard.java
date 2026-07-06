package com.gamma.agent.kernel.tool;

import java.util.List;

/**
 * A deterministic post-hoc check that a model's narration only states facts traceable to the allowed
 * {@link Evidence} (ADR-0006). Generalizes UCC's {@code NarrativeGuard} and the CxO response check:
 * the model narrates, the guard verifies, an ungrounded claim is caught (and typically fed back into a
 * repair loop) rather than surfaced.
 */
public interface GroundingGuard {

    /** Outcome: {@code grounded} when nothing is ungrounded; otherwise the offending tokens/claims. */
    record Verdict(boolean grounded, List<String> ungrounded) {
        public Verdict {
            ungrounded = (ungrounded == null) ? List.of() : List.copyOf(ungrounded);
        }
        public static Verdict ok() { return new Verdict(true, List.of()); }
    }

    /** Check {@code narration} against the {@code allowed} evidence. */
    Verdict check(String narration, List<Evidence> allowed);
}
