package com.gamma.agent;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.reason.ConfidenceEstimator;
import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;

/**
 * UCC's {@link ConfidenceEstimator} (U1.5): composes the deterministic signals UCC already produces
 * into a single confidence in {@code [0, 1]}, which the {@link com.gamma.agent.kernel.reason.EscalationPolicy}
 * compares against the capability's {@code confidenceThreshold} to decide whether to surface the
 * result or abstain.
 *
 * <h3>Signals (deterministic only — the model's self-assessment is intentionally not trusted)</h3>
 * <ul>
 *   <li><b>Status</b> — a non-OK candidate (unsupported / unavailable / a skill that already
 *       abstained) scores {@code 0.0}: there is nothing to be confident about.</li>
 *   <li><b>Validator / oracle / grounding pass</b> — {@link AgentResult#validated()} is the single
 *       flag UCC sets when a draft survived its deterministic oracle (the SQL sandbox, the cron/job
 *       validator, the alert-rule oracle) <em>and</em> the grounding guard. It is the dominant
 *       positive signal.</li>
 *   <li><b>Evidence credibility</b> — the best {@link CredibilityTier} among the grounding
 *       {@link Evidence}: authoritative catalog/report/oracle anchors count for more than indicative
 *       doc snippets, and no evidence counts for nothing.</li>
 * </ul>
 *
 * Calibration (see {@code UccConfidenceEstimatorTest}): a validated, authoritatively-grounded draft
 * scores {@code 1.0}; a read-only synthesis grounded on the catalog but not oracle-validated (e.g.
 * {@code explain-entity}) scores {@code ~0.60}; an OK answer with neither validation nor grounding
 * scores {@code 0.30} and falls below the default {@code 0.5} threshold — so it abstains rather than
 * surface an ungrounded guess.
 */
public final class UccConfidenceEstimator implements ConfidenceEstimator {

    /** Base credit for having produced an OK answer at all. */
    private static final double BASE_OK = 0.30;
    /** Credit for surviving the capability's deterministic oracle + grounding guard. */
    private static final double VALIDATED = 0.40;
    /** Maximum credit from grounding evidence (scaled by the best credibility tier). */
    private static final double EVIDENCE_MAX = 0.30;

    @Override
    public double estimate(AgentRequest request, AgentResult candidate, AgentContext ctx) {
        if (candidate == null || candidate.status() != AgentResult.Status.OK) return 0.0;
        double score = BASE_OK;
        if (candidate.validated()) score += VALIDATED;
        score += evidenceCredibility(candidate);
        return Math.min(1.0, score);
    }

    /** Credit for the best grounding tier present; {@code 0} when nothing grounds the answer. */
    private static double evidenceCredibility(AgentResult candidate) {
        CredibilityTier best = null;
        for (Evidence e : candidate.evidence()) {
            if (e.tier() == null) continue;
            if (best == null || e.tier().ordinal() < best.ordinal()) best = e.tier();
        }
        if (best == null) return 0.0;
        return switch (best) {
            case AUTHORITATIVE, OFFICIAL -> EVIDENCE_MAX;          // 0.30
            case INDICATIVE, DERIVED -> EVIDENCE_MAX * 2.0 / 3.0;  // 0.20
            case USER_PROVIDED, ASSUMPTION -> EVIDENCE_MAX / 3.0;  // 0.10
        };
    }
}
