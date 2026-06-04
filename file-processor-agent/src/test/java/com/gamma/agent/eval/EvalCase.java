package com.gamma.agent.eval;

import java.util.Map;
import java.util.Set;

/**
 * One declarative golden-eval case (U0). Loaded from JSON fixtures under {@code /eval/<intent>/cases.json}.
 *
 * <p><b>Kernel-portable shape (deliberate).</b> This mirrors the agent-kernel {@code EvalCase}/{@code Expect}
 * (see {@code AGENT_KERNEL_U0_U1_PLAN.md} §2.2/§3.7): {@code capabilityId} + {@code input} + {@code expect}.
 * The only UCC-specific addition is the {@link Model} block (the scripted {@link com.gamma.agent.model.FakeModelProvider}
 * response). At U1 the same fixtures run through the kernel's {@code agent-eval} runner — a runner swap, not a
 * fixture rewrite. {@code minConfidence} is intentionally absent until U1 makes confidence numeric.
 *
 * @param name         the case label
 * @param capabilityId the intent dispatched (kernel: {@code capabilityId}; UCC wire: {@code intent})
 * @param input        the request inputs
 * @param model        the scripted model response (or unavailable); {@code null} ⇒ a default available model
 * @param expect       the assertions
 */
public record EvalCase(String name, String capabilityId, Input input, Model model, Expect expect) {

    /** Request inputs — the fields of an {@code AssistRequest}/{@code AgentRequest}. */
    public record Input(Map<String, Object> screenContext, Map<String, Object> partialInput, String userText) {}

    /** Scripted deterministic model: a canned {@code response}, or {@code available=false} to mimic no-Ollama. */
    public record Model(String response, Boolean available) {
        public boolean isAvailable() { return available == null || available; }
    }

    /**
     * Assertions checked against the result. Any {@code null} field is not checked.
     *
     * @param status               expected status: OK / UNSUPPORTED / UNAVAILABLE
     * @param requiredDataKeys      keys that must be present in {@code result.data()}
     * @param requiredCitationRefs  citation refs ({@code Evidence.sourceRef} at U1) that must all appear
     * @param mustValidate          whether {@code result.validated()} must be true
     * @param mustAbstainWhenNoData when true, the result must be UNAVAILABLE (abstain-safe)
     * @param answerContains        substring the answer must contain
     */
    public record Expect(String status, Set<String> requiredDataKeys, Set<String> requiredCitationRefs,
                         Boolean mustValidate, Boolean mustAbstainWhenNoData, String answerContains) {}
}
