package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@link DiagnoseAndAlertSkill}'s NL→alert-rule mode (M7, C1) — CPU-only via a
 * deterministic {@link FakeModelProvider}. Asserts generate→validate→repair over the
 * {@link AlertRuleValidator}, draft-only guarantees, grounded-and-cited {@code onPipeline}, and
 * graceful model-unavailability.
 */
class DiagnoseAndAlertSkillTest {

    private final DiagnoseAndAlertSkill skill = new DiagnoseAndAlertSkill();

    private AssistContext context(SourceService svc, ModelRouter router) {
        return new AssistContext(svc.catalog(), svc.reports(), svc.statusStore(),
                new DocRetriever(Map.of()), router, svc.configSource());
    }

    private AssistRequest ask(String userText) {
        return new AssistRequest(DiagnoseAndAlertSkill.ID, Map.of(), Map.of(), userText);
    }

    @Test
    void validRequestProducesValidatedDraftThatRoundTrips(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                    "{\"name\":\"high-error-rate\",\"metric\":\"error_rate\",\"comparator\":\"gt\","
                            + "\"threshold\":0.05,\"window\":\"1h\",\"severity\":\"CRITICAL\",\"on_pipeline\":null}"));
            AssistResult res = skill.run(ask("warn me when the error rate goes above 5%"), context(svc, router));

            assertEquals(AssistResult.Status.OK, res.status());
            assertTrue(res.validated(), "the draft passed the alert-rule oracle");
            assertNull(res.applyVia(), "draft-only (V-9): no write endpoint");

            Map<String, Object> data = res.data();
            assertEquals("error_rate", data.get("metric"));
            assertEquals("gt", data.get("comparator"));
            assertEquals(0.05, ((Number) data.get("threshold")).doubleValue(), 1e-9);
            assertEquals("CRITICAL", data.get("severity"));
            assertFalse(data.containsKey("onPipeline"), "no pipeline named -> rule spans all");
            assertTrue(String.valueOf(data.get("humanReadable")).contains("error_rate"));

            // The draft .toon the user saves carries the rule under an 'alert' section.
            String draftToon = (String) data.get("draftToon");
            assertNotNull(draftToon);
            Map<String, Object> roundTrip = ConfigCodec.toMap(draftToon);
            assertTrue(roundTrip.containsKey("alert"), "draft .toon has an alert section: " + draftToon);

            assertTrue(res.citations().stream().anyMatch(c -> c.source().equals("oracle")),
                    "cites the validating oracle: " + res.citations());
        }
    }

    @Test
    void groundsAndCitesPipeline(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            MetadataNode source = svc.catalog().nodesOfKind(NodeKind.SOURCE).get(0);
            String pipeName = source.label();
            String pipeId = source.id();

            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                    "{\"name\":\"slow-batches\",\"metric\":\"duration_ms\",\"comparator\":\"gt\","
                            + "\"threshold\":60000,\"window\":\"1d\",\"severity\":\"WARNING\","
                            + "\"on_pipeline\":\"" + pipeName + "\"}"));
            AssistResult res = skill.run(
                    ask("warn if batches on " + pipeName + " take over a minute"), context(svc, router));

            assertEquals(AssistResult.Status.OK, res.status());
            assertEquals(pipeName, res.data().get("onPipeline"));
            assertTrue(res.citations().stream()
                            .anyMatch(c -> c.source().equals("catalog") && c.ref().equals(pipeId)),
                    "cites the grounded pipeline node: " + res.citations());
            assertTrue(res.links().contains("/catalog/tables/" + pipeId));
        }
    }

    @Test
    void outOfRangeThresholdIsRepairedNotSurfaced(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AtomicInteger round = new AtomicInteger();
            // Round 1: error_rate threshold 5 (>1) is rejected. Round 2: 0.05 is valid.
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                    round.incrementAndGet() == 1
                            ? "{\"name\":\"r\",\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":5,"
                              + "\"window\":\"1h\",\"severity\":\"WARNING\"}"
                            : "{\"name\":\"r\",\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.05,"
                              + "\"window\":\"1h\",\"severity\":\"WARNING\"}"));
            AssistResult res = skill.run(ask("alert on high error rate"), context(svc, router));

            assertEquals(AssistResult.Status.OK, res.status(), "the bad threshold was repaired, not surfaced");
            assertEquals(0.05, ((Number) res.data().get("threshold")).doubleValue(), 1e-9);
            assertEquals(Boolean.TRUE, res.data().get("repaired"));
            assertTrue(round.get() >= 2, "the loop re-prompted after the oracle rejection");
        }
    }

    @Test
    void hallucinatedPipelineIsRejectedByGrounding(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AtomicInteger round = new AtomicInteger();
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                    round.incrementAndGet() == 1
                            ? "{\"name\":\"r\",\"metric\":\"failed_batches\",\"comparator\":\"gte\",\"threshold\":1,"
                              + "\"window\":\"1d\",\"severity\":\"CRITICAL\",\"on_pipeline\":\"ghost_pipeline\"}"
                            : "{\"name\":\"r\",\"metric\":\"failed_batches\",\"comparator\":\"gte\",\"threshold\":1,"
                              + "\"window\":\"1d\",\"severity\":\"CRITICAL\",\"on_pipeline\":null}"));
            AssistResult res = skill.run(ask("alert on any failed batch"), context(svc, router));

            assertEquals(AssistResult.Status.OK, res.status());
            assertFalse(res.data().containsKey("onPipeline"), "the fabricated pipeline was dropped");
            assertTrue(round.get() >= 2, "grounding rejection forced a repair round");
            assertTrue(res.citations().stream().noneMatch(c -> c.source().equals("catalog")),
                    "no catalog citation when no real pipeline was used");
        }
    }

    @Test
    void modelUnavailableYieldsUnavailableNotError(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.down());
            AssistResult res = skill.run(ask("warn on errors"), context(svc, router));
            assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
            assertTrue(res.message().toLowerCase().contains("not available"));
        }
    }

    @Test
    void blankRequestIsRejectedCleanly(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned("{}"));
            AssistResult res = skill.run(ask("   "), context(svc, router));
            assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
            assertTrue(res.message().contains("userText"));
        }
    }
}
