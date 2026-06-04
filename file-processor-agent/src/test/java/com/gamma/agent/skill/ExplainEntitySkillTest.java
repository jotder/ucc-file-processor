package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.service.SourceService;
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

    private AssistContext context(SourceService svc, ModelRouter router) {
        return new AssistContext(svc.catalog(), svc.reports(), svc.statusStore(),
                new DocRetriever(Map.of()), router, svc.configSource());
    }

    private AssistRequest explain(String question) {
        return new AssistRequest(ExplainEntitySkill.ID,
                Map.of("entityType", "table", "id", EVENT_ID), Map.of(), question);
    }

    @Test
    void answersFromCatalogWithDerivedCitations(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            // The fake echoes the prompt back, so we can assert the catalog grounding reached the model.
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding(ModelRequest::prompt));
            AssistResult res = new ExplainEntitySkill().run(explain("what is this table?"), context(svc, router));

            assertEquals(AssistResult.Status.OK, res.status());
            assertNull(res.applyVia(), "read-only skill carries no write endpoint");
            assertFalse(res.validated(), "no oracle ran for read-only synthesis");

            // citation points at the real event node...
            assertTrue(res.citations().stream()
                            .anyMatch(c -> c.source().equals("catalog") && c.ref().equals(EVENT_ID)),
                    "cites the event node: " + res.citations());
            assertTrue(res.links().contains("/catalog/tables/" + EVENT_ID));

            // ...and the grounding (node id + the source neighbour) actually reached the model prompt.
            assertTrue(res.answer().contains(EVENT_ID), "node grounded into prompt");
            assertTrue(res.answer().contains("source:mini_etl"),
                    "neighbour grounded into prompt: " + res.answer());
        }
    }

    @Test
    void cannedModelOutputIsReturnedVerbatim(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned("This table stores mini events."));
            AssistResult res = new ExplainEntitySkill().run(explain("explain"), context(svc, router));
            assertEquals("This table stores mini events.", res.answer());
        }
    }

    @Test
    void modelUnavailableYieldsUnavailableNotAnError(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.down());
            AssistResult res = new ExplainEntitySkill().run(explain("explain"), context(svc, router));
            assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
            assertNull(res.answer());
            assertTrue(res.message().toLowerCase().contains("not available"));
        }
    }
}
