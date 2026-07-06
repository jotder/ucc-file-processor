package com.gamma.agent.kernel.eval;

import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;

import java.util.Set;

/**
 * One golden-eval case: a named {@code input} request against {@code capabilityId}, plus the
 * {@link Expect} assertions. Loadable from JSON fixtures via {@link EvalCaseLoader}.
 */
public record EvalCase(String name, String capabilityId, AgentRequest input, Expect expect) {

    /**
     * Expectations checked against the produced {@link AgentResult}. Any field left {@code null} is not
     * checked, so a fixture asserts only what it cares about.
     *
     * @param status                expected result status
     * @param requiredDataKeys      keys that must be present in {@code result.data()}
     * @param requiredEvidenceRefs  {@code Evidence.sourceRef} values that must all appear
     * @param mustValidate          whether {@code result.validated()} must be true
     * @param minConfidence         minimum {@code result.confidence()}
     * @param mustAbstainWhenNoData when true, the result must be UNAVAILABLE (abstain)
     */
    public record Expect(AgentResult.Status status, Set<String> requiredDataKeys,
                         Set<String> requiredEvidenceRefs, Boolean mustValidate,
                         Double minConfidence, Boolean mustAbstainWhenNoData) {}
}
