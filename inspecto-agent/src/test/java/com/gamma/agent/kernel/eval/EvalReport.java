package com.gamma.agent.kernel.eval;

import java.util.List;

/** The outcome of running a set of {@link EvalCase}s: totals and the per-case failures. */
public record EvalReport(int total, int passed, List<Failure> failures) {

    public EvalReport {
        failures = (failures == null) ? List.of() : List.copyOf(failures);
    }

    /** One failed case and why. */
    public record Failure(String caseName, String reason) {}

    public boolean allPassed() { return failures.isEmpty(); }
}
