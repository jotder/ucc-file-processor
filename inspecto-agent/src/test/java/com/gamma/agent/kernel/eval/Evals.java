package com.gamma.agent.kernel.eval;

import com.gamma.agent.kernel.agent.AgentContext;
import com.gamma.agent.kernel.agent.CapabilityRegistry;
import org.junit.jupiter.api.DynamicTest;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * JUnit glue: turn a set of {@link EvalCase}s into a stream of {@link DynamicTest}s a consumer drives
 * with {@code @TestFactory}. Ships in this module's test-jar so consumers reuse it from their test scope.
 */
public final class Evals {

    private Evals() {}

    public static Stream<DynamicTest> asTests(CapabilityRegistry registry, AgentContext ctx,
                                              List<EvalCase> cases) {
        EvalRunner runner = new EvalRunner();
        return cases.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            EvalReport report = runner.run(registry, ctx, List.of(c));
            if (!report.allPassed()) {
                fail(c.name() + ": " + report.failures().get(0).reason());
            }
        }));
    }
}
