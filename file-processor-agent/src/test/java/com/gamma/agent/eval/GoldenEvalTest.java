package com.gamma.agent.eval;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The U0 golden-eval net (Gap E): declarative JSON fixtures for all 7 capabilities, run against the real
 * agent in-process with a deterministic model. Establishes the behavioral regression baseline that the
 * U1 kernel reshape happens under — the same fixtures port to the kernel's {@code agent-eval} runner.
 *
 * <p>Each fixture file is a JSON array of {@link EvalCase} under {@code /eval/<intent>/cases.json}.
 */
class GoldenEvalTest {

    private static final List<String> SUITES = List.of(
            "explain-entity",
            "kpi-to-sql",
            "nl-to-schedule",
            "suggest-config",
            "report-sql",
            "report-narrative",
            "diagnose-and-alert",
            "_cross");

    @TestFactory
    Stream<DynamicTest> goldenEval() {
        return SUITES.stream().flatMap(suite ->
                GoldenEvalHarness.load("/eval/" + suite + "/cases.json").stream()
                        .map(c -> DynamicTest.dynamicTest(suite + " / " + c.name(), () -> {
                            Optional<String> failure = GoldenEvalHarness.run(c);
                            assertTrue(failure.isEmpty(), () -> failure.orElse(""));
                        })));
    }
}
