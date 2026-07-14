package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.retrieve.DocRetriever;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2 golden tests for {@code explain-entity}, CPU-only (a {@link FakeModelProvider}, no Ollama).
 * They prove the skill grounds on the real metadata catalog and that citations are <em>derived</em>
 * from the sources fed to the model (so they always point at real catalog node ids), independent of
 * what the model says.
 */
class ExplainEntitySkillTest {

    private static final String EVENT_ID = "event:mini_etl/mini";

    private UccAgentContext context(CollectorService svc, ModelRouter router) {
        return new UccAgentContext(svc.catalog(), svc.reports(), svc.statusStore(),
                new DocRetriever(Map.of()), router, svc.configSource());
    }

    private AgentRequest explain(String question) {
        return new AgentRequest(ExplainEntitySkill.ID,
                Map.of("entityType", "table", "id", EVENT_ID), Map.of(), question);
    }

    @Test
    void answersFromCatalogWithDerivedCitations(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            // The fake echoes the prompt back, so we can assert the catalog grounding reached the model.
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding(ModelRequest::prompt));
            AgentResult res = new ExplainEntitySkill().run(explain("what is this table?"), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status());
            assertNull(res.applyVia(), "read-only skill carries no write endpoint");
            assertFalse(res.validated(), "no oracle ran for read-only synthesis");

            // evidence points at the real event node...
            assertTrue(res.evidence().stream()
                            .anyMatch(c -> c.effectiveTierLabel().equals("catalog") && c.sourceRef().equals(EVENT_ID)),
                    "cites the event node: " + res.evidence());
            assertTrue(res.links().contains("/catalog/tables/" + EVENT_ID));

            // ...and the grounding (node id + the source neighbour) actually reached the model prompt.
            assertTrue(res.answer().contains(EVENT_ID), "node grounded into prompt");
            assertTrue(res.answer().contains("stream:mini_etl"),
                    "neighbour grounded into prompt: " + res.answer());
        }
    }

    @Test
    void cannedModelOutputIsReturnedVerbatim(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned("This table stores mini events."));
            AgentResult res = new ExplainEntitySkill().run(explain("explain"), context(svc, router));
            assertEquals("This table stores mini events.", res.answer());
        }
    }

    @Test
    void modelUnavailableYieldsUnavailableNotAnError(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.down());
            AgentResult res = new ExplainEntitySkill().run(explain("explain"), context(svc, router));
            assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
            assertNull(res.answer());
            assertTrue(res.message().toLowerCase().contains("not available"));
        }
    }
}
