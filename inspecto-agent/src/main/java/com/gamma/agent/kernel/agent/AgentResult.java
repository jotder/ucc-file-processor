package com.gamma.agent.kernel.agent;

import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.tool.Evidence;

import java.util.List;
import java.util.Map;

/**
 * The neutral output of a {@link Capability}. {@link #status} drives the app's wire mapping;
 * {@link #confidence} is a real number (not a label); {@link #applyVia} is the endpoint that applies a
 * write-bearing suggestion ({@code null} for read-only/draft). {@link #data} carries a capability-specific
 * structured payload alongside the prose {@link #answer}.
 */
public record AgentResult(String capabilityId, int version, Status status, String answer,
                          List<Evidence> evidence, List<String> links, String rationale,
                          double confidence, boolean validated, ModelTier servedBy, String applyVia,
                          AgentError.Category error, String message, Map<String, Object> data) {

    /** Outcome discriminator. */
    public enum Status { OK, UNSUPPORTED, UNAVAILABLE }

    public AgentResult {
        evidence = (evidence == null) ? List.of() : List.copyOf(evidence);
        links = (links == null) ? List.of() : List.copyOf(links);
        data = (data == null) ? Map.of() : Map.copyOf(data);
    }

    /** A copy with the confidence replaced (used by the escalation policy after estimation). */
    public AgentResult withConfidence(double confidence) {
        return new AgentResult(capabilityId, version, status, answer, evidence, links, rationale,
                confidence, validated, servedBy, applyVia, error, message, data);
    }

    /** No capability is registered for this id (or no agent present). */
    public static AgentResult unsupported(String capabilityId) {
        return new AgentResult(capabilityId, 0, Status.UNSUPPORTED, null, List.of(), List.of(),
                null, 0.0, false, null, null, null,
                "no capability is registered for '" + capabilityId + "'", Map.of());
    }

    /** The capability exists but cannot produce an answer (model/provider unavailable, or abstain). */
    public static AgentResult unavailable(String capabilityId, String message) {
        return new AgentResult(capabilityId, 0, Status.UNAVAILABLE, null, List.of(), List.of(),
                null, 0.0, false, null, null, null, message, Map.of());
    }

    /** A validated answer with grounding evidence and follow-up links. */
    public static AgentResult ok(String capabilityId, int version, String answer, List<Evidence> evidence,
                                 List<String> links, String rationale, double confidence, ModelTier servedBy) {
        return new AgentResult(capabilityId, version, Status.OK, answer, evidence, links, rationale,
                confidence, true, servedBy, null, null, null, Map.of());
    }

    /** A validated draft result carrying a structured {@code data} payload the user can review/save. */
    public static AgentResult draft(String capabilityId, int version, String answer, List<Evidence> evidence,
                                    List<String> links, String rationale, double confidence,
                                    ModelTier servedBy, Map<String, Object> data) {
        return new AgentResult(capabilityId, version, Status.OK, answer, evidence, links, rationale,
                confidence, true, servedBy, null, null, null, data);
    }
}
