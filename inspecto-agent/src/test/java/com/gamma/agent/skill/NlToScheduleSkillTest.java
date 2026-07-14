package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.agent.kernel.retrieve.DocRetriever;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.job.JobConfig;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@link NlToScheduleSkill} (M4, A2) — fully CPU-only via a deterministic
 * {@link FakeModelProvider}; no Ollama. Asserts the generate → validate → repair contract, the
 * draft-only guarantees, grounded-and-cited {@code on_pipeline}, and graceful model-unavailability.
 */
class NlToScheduleSkillTest {

    private final NlToScheduleSkill skill = new NlToScheduleSkill(ZoneId.of("UTC"));

    private UccAgentContext context(CollectorService svc, ModelRouter router) {
        return new UccAgentContext(svc.catalog(), svc.reports(), svc.statusStore(),
                new DocRetriever(Map.of()), router, svc.configSource());
    }

    private AgentRequest ask(String userText) {
        return new AgentRequest(NlToScheduleSkill.ID, Map.of(), Map.of(), userText);
    }

    @Test
    void plainCaseProducesValidatedDraftThatRoundTrips(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                    "{\"name\":\"nightly-clean\",\"cron\":\"0 2 * * *\",\"job_type\":\"maintenance\"}"));
            AgentResult res = skill.run(ask("clean up every day at 2am"), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status());
            assertTrue(res.validated(), "the draft ran through the cron + job oracle");
            assertNull(res.applyVia(), "draft-only (V-9): no write endpoint");

            Map<String, Object> data = res.data();
            assertEquals("0 2 * * *", data.get("cron"));
            assertEquals("maintenance", data.get("jobType"));
            assertEquals("every day at 02:00", data.get("humanReadable"));
            assertFalse(data.containsKey("onPipeline"), "no upstream pipeline in this request");

            @SuppressWarnings("unchecked")
            List<String> nextRuns = (List<String>) data.get("nextRuns");
            assertEquals(5, nextRuns.size(), "five upcoming fire times");

            // The draft .toon the user saves must itself parse back into a JobConfig.
            String draftToon = (String) data.get("draftToon");
            assertNotNull(draftToon);
            JobConfig parsed = JobConfig.fromMap(ConfigCodec.toMap(draftToon));
            assertEquals("nightly-clean", parsed.name());
            assertEquals("0 2 * * *", parsed.cron());

            // Citation discipline: the cron was validated by the oracle (derived, not parsed).
            assertTrue(res.evidence().stream().anyMatch(c -> c.effectiveTierLabel().equals("oracle")),
                    "cites the validating oracle: " + res.evidence());
        }
    }

    @Test
    void compositionalCaseGroundsAndCitesPipeline(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            MetadataNode source = svc.catalog().nodesOfKind(NodeKind.STREAM).get(0);
            String pipeName = source.label();   // the real pipeline name the model must reuse
            String pipeId = source.id();

            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                    "{\"name\":\"weekday-enrich\",\"cron\":\"0 6 * * MON-FRI\",\"job_type\":\"enrich\","
                            + "\"on_pipeline\":\"" + pipeName + "\"}"));
            AgentResult res = skill.run(
                    ask("every weekday at 6am after " + pipeName), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status());
            Map<String, Object> data = res.data();
            assertEquals("0 6 * * MON-FRI", data.get("cron"));
            assertEquals(pipeName, data.get("onPipeline"));
            assertEquals("every day at 06:00 on weekdays", data.get("humanReadable"));

            // The grounded pipeline node id is cited + linked — derived from the catalog, not the model.
            assertTrue(res.evidence().stream()
                            .anyMatch(c -> c.effectiveTierLabel().equals("catalog") && c.sourceRef().equals(pipeId)),
                    "cites the grounded pipeline node: " + res.evidence());
            assertTrue(res.links().contains("/catalog/tables/" + pipeId));
        }
    }

    @Test
    void invalidCronFromModelIsRepairedNotSurfaced(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            AtomicInteger round = new AtomicInteger();
            // Round 1: hour 99 is out of range -> the cron oracle rejects it. Round 2: valid.
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                    round.incrementAndGet() == 1
                            ? "{\"name\":\"j\",\"cron\":\"0 99 * * *\",\"job_type\":\"report\"}"
                            : "{\"name\":\"j\",\"cron\":\"0 9 * * *\",\"job_type\":\"report\"}"));
            AgentResult res = skill.run(ask("daily report at 9am"), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status(), "the bad cron was repaired, not surfaced");
            assertEquals("0 9 * * *", res.data().get("cron"));
            assertEquals(Boolean.TRUE, res.data().get("repaired"), "took a repair round");
            assertTrue(round.get() >= 2, "the loop re-prompted after the oracle rejection");
        }
    }

    @Test
    void hallucinatedPipelineIsRejectedByGrounding(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            AtomicInteger round = new AtomicInteger();
            // Round 1 invents a pipeline that isn't in the catalog -> rejected. Round 2 drops it.
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                    round.incrementAndGet() == 1
                            ? "{\"name\":\"j\",\"cron\":\"0 3 * * *\",\"job_type\":\"enrich\",\"on_pipeline\":\"ghost_pipeline\"}"
                            : "{\"name\":\"j\",\"cron\":\"0 3 * * *\",\"job_type\":\"enrich\",\"on_pipeline\":null}"));
            AgentResult res = skill.run(ask("enrich daily at 3am"), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status());
            assertFalse(res.data().containsKey("onPipeline"), "the fabricated pipeline was dropped");
            assertTrue(round.get() >= 2, "grounding rejection forced a repair round");
            assertTrue(res.evidence().stream().noneMatch(c -> c.effectiveTierLabel().equals("catalog")),
                    "no catalog citation when no real pipeline was used");
        }
    }

    @Test
    void modelUnavailableYieldsUnavailableNotError(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.down());
            AgentResult res = skill.run(ask("daily at 2am"), context(svc, router));
            assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
            assertNull(res.answer());
            assertTrue(res.message().toLowerCase().contains("not available"));
        }
    }

    @Test
    void blankRequestIsRejectedCleanly(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned("{}"));
            AgentResult res = skill.run(ask("   "), context(svc, router));
            assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
            assertTrue(res.message().contains("userText"));
        }
    }

    @Test
    void tierRoutingEscalatesCompositionalPhrasing() {
        assertEquals(ModelTier.SMALL, NlToScheduleSkill.routeTier("every day at 2am"));
        assertEquals(ModelTier.SMALL, NlToScheduleSkill.routeTier("every 15 minutes"));
        assertEquals(ModelTier.MEDIUM, NlToScheduleSkill.routeTier("every weekday at 6am after adjustment_etl"));
        assertEquals(ModelTier.MEDIUM, NlToScheduleSkill.routeTier("at 9am Eastern time"));
        assertEquals(ModelTier.MEDIUM, NlToScheduleSkill.routeTier("the first business day of the month"));
    }
}
