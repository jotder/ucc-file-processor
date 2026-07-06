package com.gamma.agent.diagnose;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.assist.Diagnosis;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.etl.BatchEvent;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@link ModelDiagnoser} (M7, event-driven mode) — CPU-only. Proves the abstain-safe
 * contract: with no model the deterministic heuristic stands alone; with a (fake) model the prose is
 * enriched and the pipeline citation is derived from the catalog (never fabricated).
 */
class ModelDiagnoserTest {

    private static final long EPOCH = 1_700_000_000_000L;

    private static BatchEvent failed(String pipeline) {
        return new BatchEvent(pipeline, "B1", "FAILED", List.of(), 0, 10L, 1,
                "schema selector mismatch", "bad.csv", 3);
    }

    @Test
    void modelDownReturnsHeuristicOnly(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.down());
            ModelDiagnoser diag = new ModelDiagnoser(router, svc.catalog(), () -> EPOCH);

            Diagnosis d = diag.diagnose(failed(svc.catalog().nodesOfKind(NodeKind.SOURCE).get(0).label()));
            assertTrue(d.heuristicOnly(), "no model contributed");
            assertEquals(Diagnosis.Severity.CRITICAL, d.severity(), "FAILED w/ no output");
            assertTrue(d.rootCause().toLowerCase().contains("schema/selector mismatch"));
            assertEquals(EPOCH, d.epochMillis());
        }
    }

    @Test
    void modelAvailableEnrichesProseAndGroundsPipeline(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            MetadataNode source = svc.catalog().nodesOfKind(NodeKind.SOURCE).get(0);
            String pipeName = source.label();
            String pipeId = source.id();

            String prose = "The input file's columns no longer match the configured schema; "
                    + "reconcile the selectors and re-run.";
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(prose));
            ModelDiagnoser diag = new ModelDiagnoser(router, svc.catalog(), () -> EPOCH);

            Diagnosis d = diag.diagnose(failed(pipeName));
            assertFalse(d.heuristicOnly(), "the model enriched the prose");
            assertEquals(prose, d.rootCause());
            assertEquals(Diagnosis.Severity.CRITICAL, d.severity(), "severity stays the deterministic heuristic's");
            assertTrue(d.citations().stream()
                            .anyMatch(c -> c.source().equals("catalog") && c.ref().equals(pipeId)),
                    "pipeline SOURCE node cited (derived, not fabricated): " + d.citations());
        }
    }

    @Test
    void modelThrowingFallsBackToHeuristic(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            // Available-but-broken model (network flake, provider 500): the deterministic
            // heuristic diagnosis must still be recorded, flagged heuristicOnly (B3 gap test, v4.1).
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding(r -> {
                throw new RuntimeException("simulated provider outage");
            }));
            ModelDiagnoser diag = new ModelDiagnoser(router, svc.catalog(), () -> EPOCH);

            Diagnosis d = diag.diagnose(failed(svc.catalog().nodesOfKind(NodeKind.SOURCE).get(0).label()));
            assertTrue(d.heuristicOnly(), "model failure must degrade to the heuristic");
            assertEquals(Diagnosis.Severity.CRITICAL, d.severity());
            assertTrue(d.rootCause().toLowerCase().contains("schema/selector mismatch"));
        }
    }
}
