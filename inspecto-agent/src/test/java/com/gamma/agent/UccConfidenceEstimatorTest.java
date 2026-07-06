package com.gamma.agent;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.Capability;
import com.gamma.agent.kernel.agent.CapabilitySpec;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.reason.EscalationPolicy;
import com.gamma.agent.kernel.reason.EscalationRung;
import com.gamma.agent.kernel.tool.CredibilityTier;
import com.gamma.agent.kernel.tool.Evidence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U1.5: proves the {@link UccConfidenceEstimator} scoring and the {@link EscalationPolicy} abstain
 * gate UCC wires around it — a validated, authoritatively-grounded result clears the threshold and is
 * surfaced (with the estimated confidence), while an OK-but-ungrounded result falls below and abstains
 * to {@code UNAVAILABLE} rather than shipping a low-confidence guess.
 */
class UccConfidenceEstimatorTest {

    private static final UccConfidenceEstimator EST = new UccConfidenceEstimator();
    private static final AgentRequest REQ = new AgentRequest("x", Map.of(), Map.of(), null);
    private static final AgentContext CTX = AgentContext.builder().build();

    private static AgentResult ok(boolean validated, CredibilityTier tier) {
        List<Evidence> ev = (tier == null) ? List.of() : List.of(Evidence.of("n", tier, "n"));
        return new AgentResult("x", 1, AgentResult.Status.OK, "answer", ev, List.of(), null,
                1.0, validated, ModelTier.MEDIUM, null, null, null, Map.of());
    }

    // ── estimator scoring ────────────────────────────────────────────────────────────────

    @Test
    void nonOkScoresZero() {
        assertEquals(0.0, EST.estimate(REQ, AgentResult.unavailable("x", "down"), CTX), 1e-9);
        assertEquals(0.0, EST.estimate(REQ, AgentResult.unsupported("x"), CTX), 1e-9);
    }

    @Test
    void validatedAndAuthoritativeScoresFull() {
        // 0.30 base + 0.40 validated + 0.30 authoritative = 1.0
        assertEquals(1.0, EST.estimate(REQ, ok(true, CredibilityTier.AUTHORITATIVE), CTX), 1e-9);
    }

    @Test
    void groundedButUnvalidatedClearsThreshold() {
        // explain-entity shape: 0.30 base + 0.30 authoritative grounding = 0.60 (≥ 0.5)
        assertEquals(0.60, EST.estimate(REQ, ok(false, CredibilityTier.AUTHORITATIVE), CTX), 1e-9);
    }

    @Test
    void ungroundedUnvalidatedFallsBelowThreshold() {
        // 0.30 base only — nothing validated it, nothing grounds it
        assertEquals(0.30, EST.estimate(REQ, ok(false, null), CTX), 1e-9);
    }

    @Test
    void derivedEvidenceScoresLessThanAuthoritative() {
        assertEquals(0.50, EST.estimate(REQ, ok(false, CredibilityTier.DERIVED), CTX), 1e-9);
    }

    // ── escalation abstain gate ─────────────────────────────────────────────────────────

    @Test
    void lowConfidenceOkResultAbstains() {
        EscalationPolicy policy = new EscalationPolicy(List.of(new EscalationRung.Abstain()));
        Capability weak = stub("weak", 0.5, ok(false, null));            // scores 0.30
        AgentResult out = policy.run(weak, REQ, CTX, EST);
        assertEquals(AgentResult.Status.UNAVAILABLE, out.status(),
                "an ungrounded, unvalidated OK answer abstains rather than surfacing");
    }

    @Test
    void highConfidenceOkResultIsSurfacedWithEstimatedConfidence() {
        EscalationPolicy policy = new EscalationPolicy(List.of(new EscalationRung.Abstain()));
        Capability strong = stub("strong", 0.5, ok(true, CredibilityTier.AUTHORITATIVE)); // 1.0
        AgentResult out = policy.run(strong, REQ, CTX, EST);
        assertEquals(AgentResult.Status.OK, out.status());
        assertEquals(1.0, out.confidence(), 1e-9, "the estimator's value replaces the placeholder");
    }

    // ── a fixed-result stub capability ──────────────────────────────────────────────────

    private static Capability stub(String id, double threshold, AgentResult result) {
        CapabilitySpec spec = new CapabilitySpec(id, 1, "stub", ModelTier.MEDIUM, threshold,
                Duration.ofSeconds(60), Set.of(), Set.of());
        return new Capability() {
            @Override public CapabilitySpec spec() { return spec; }
            @Override public AgentResult run(AgentRequest request, AgentContext context) { return result; }
        };
    }
}
