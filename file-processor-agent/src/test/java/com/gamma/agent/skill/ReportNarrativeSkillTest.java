package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.model.ModelRequest;
import com.gamma.agent.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@code report-narrative} (M8), CPU-only via a {@link FakeModelProvider}. The point
 * of the skill is proven here: a hallucinated figure is caught by the deterministic {@link NarrativeGuard}
 * and repaired (never surfaced); with no model the skill still returns a deterministic, grounded
 * template narrative; and a real {@link com.gamma.report.ReportService} report can be resolved by
 * selector and narrated.
 */
class ReportNarrativeSkillTest {

    private final ReportNarrativeSkill skill = new ReportNarrativeSkill();

    private AssistContext ctx(SourceService svc, ModelRouter router) {
        return new AssistContext(svc == null ? null : svc.catalog(),
                svc == null ? null : svc.reports(),
                svc == null ? null : svc.statusStore(),
                new DocRetriever(Map.of()), router,
                svc == null ? null : svc.configSource());
    }

    /** A request carrying a raw report object to narrate (no ReportService needed). */
    private static AssistRequest rawReport(Map<String, Object> report) {
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("report", report);
        partial.put("reportType", "service");
        return new AssistRequest(ReportNarrativeSkill.ID, Map.of(), partial, null);
    }

    private static Map<String, Object> sampleReport() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalBatches", 412);
        r.put("success", 405);
        r.put("failed", 7);
        r.put("errorRate", 0.017);
        return r;
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────

    @Test
    void groundedNarrativePasses() {
        String canned = "Across 412 batches, 405 succeeded and 7 failed (a 1.7% error rate).";
        AssistResult res = skill.run(rawReport(sampleReport()),
                ctx(null, ModelRouter.of(FakeModelProvider.canned(canned))));

        assertEquals(AssistResult.Status.OK, res.status(), res.message());
        assertNull(res.applyVia(), "draft-only");
        assertEquals(canned, res.data().get("narrative"));
        assertEquals(Boolean.TRUE, res.data().get("modelBacked"));
        assertEquals(Boolean.FALSE, res.data().get("repaired"));
    }

    @Test
    void hallucinatedNumberIsRepairedNotSurfaced() {
        AtomicInteger round = new AtomicInteger();
        ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                round.incrementAndGet() == 1
                        ? "A whopping 9999 batches failed catastrophically."   // 9999 not in report
                        : "Of 412 batches, 7 failed."));                        // grounded
        AssistResult res = skill.run(rawReport(sampleReport()), ctx(null, router));

        assertEquals(AssistResult.Status.OK, res.status(), "the hallucinated figure was repaired");
        assertEquals(Boolean.TRUE, res.data().get("repaired"));
        assertFalse(String.valueOf(res.data().get("narrative")).contains("9999"),
                "the invented number is never surfaced");
    }

    @Test
    void persistentlyHallucinatingModelFailsGracefully() {
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned("There were 9999 failures."));
        AssistResult res = skill.run(rawReport(sampleReport()), ctx(null, router));

        assertEquals(AssistResult.Status.UNAVAILABLE, res.status(),
                "an always-ungrounded narrative is never surfaced");
    }

    @Test
    void noModelFallsBackToDeterministicTemplate() {
        AssistResult res = skill.run(rawReport(sampleReport()),
                ctx(null, ModelRouter.of(FakeModelProvider.down())));

        assertEquals(AssistResult.Status.OK, res.status(), "abstain-safe: a template is produced");
        assertEquals(Boolean.FALSE, res.data().get("modelBacked"));
        String narrative = String.valueOf(res.data().get("narrative"));
        assertTrue(narrative.contains("412") && narrative.contains("7"),
                "the template restates the report's own figures: " + narrative);
    }

    @Test
    void selectorModeResolvesAndNarratesARealReport(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            // A fresh service has run nothing → the service report is all zeros (grounded numbers).
            String canned = "No batches have run yet: 0 total, 0 succeeded, 0 failed.";
            AssistRequest req = new AssistRequest(ReportNarrativeSkill.ID, Map.of(),
                    Map.of("reportType", "service"), null);
            AssistResult res = skill.run(req, ctx(svc, ModelRouter.of(FakeModelProvider.canned(canned))));

            assertEquals(AssistResult.Status.OK, res.status(), res.message());
            assertEquals("service", res.data().get("reportType"));
            assertEquals(canned, res.data().get("narrative"));
            assertTrue(res.citations().stream().anyMatch(c -> c.source().equals("report")));
        }
    }

    @Test
    void missingReportTypeAndReportIsGraceful() {
        AssistRequest req = new AssistRequest(ReportNarrativeSkill.ID, Map.of(), Map.of(), null);
        AssistResult res = skill.run(req, ctx(null, ModelRouter.of(FakeModelProvider.canned("x"))));
        assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
    }
}
