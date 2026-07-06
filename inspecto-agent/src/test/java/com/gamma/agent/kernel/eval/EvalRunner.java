package com.gamma.agent.kernel.eval;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.agent.CapabilityRegistry;
import com.gamma.agent.kernel.tool.Evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs {@link EvalCase}s against a {@link CapabilityRegistry} and an {@link AgentContext}, checking
 * each case's {@link EvalCase.Expect} and collecting failures. Deterministic given a deterministic
 * model (e.g. {@link FakeModelProvider}).
 */
public final class EvalRunner {

    public EvalReport run(CapabilityRegistry registry, AgentContext ctx, List<EvalCase> cases) {
        List<EvalReport.Failure> failures = new ArrayList<>();
        int passed = 0;
        for (EvalCase c : cases) {
            String reason = check(registry, ctx, c);
            if (reason == null) passed++;
            else failures.add(new EvalReport.Failure(c.name(), reason));
        }
        return new EvalReport(cases.size(), passed, failures);
    }

    /** Returns {@code null} if the case passes, else the first failed expectation as a message. */
    private static String check(CapabilityRegistry registry, AgentContext ctx, EvalCase c) {
        AgentResult result;
        try {
            result = registry.dispatch(c.input(), ctx);
        } catch (RuntimeException e) {
            return "threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        EvalCase.Expect ex = c.expect();
        if (ex == null) return null;

        if (Boolean.TRUE.equals(ex.mustAbstainWhenNoData())
                && result.status() != AgentResult.Status.UNAVAILABLE) {
            return "expected abstain (UNAVAILABLE) but was " + result.status();
        }
        if (ex.status() != null && result.status() != ex.status()) {
            return "status expected " + ex.status() + " but was " + result.status();
        }
        if (Boolean.TRUE.equals(ex.mustValidate()) && !result.validated()) {
            return "expected validated=true";
        }
        if (ex.minConfidence() != null && result.confidence() < ex.minConfidence()) {
            return "confidence " + result.confidence() + " below min " + ex.minConfidence();
        }
        if (ex.requiredDataKeys() != null && !result.data().keySet().containsAll(ex.requiredDataKeys())) {
            return "missing data keys; expected " + ex.requiredDataKeys() + " present " + result.data().keySet();
        }
        if (ex.requiredEvidenceRefs() != null) {
            Set<String> refs = result.evidence().stream()
                    .map(Evidence::sourceRef).collect(Collectors.toSet());
            if (!refs.containsAll(ex.requiredEvidenceRefs())) {
                return "missing evidence refs; expected " + ex.requiredEvidenceRefs() + " present " + refs;
            }
        }
        return null;
    }
}
