package com.gamma.agent.eval;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.skill.DiagnoseAndAlertSkill;
import com.gamma.agent.skill.ExplainEntitySkill;
import com.gamma.agent.skill.KpiToSqlSkill;
import com.gamma.agent.skill.NlToScheduleSkill;
import com.gamma.agent.skill.ReportNarrativeSkill;
import com.gamma.agent.skill.ReportSqlSkill;
import com.gamma.agent.skill.SuggestConfigSkill;
import com.gamma.agent.skill.UccAgentContext;
import com.gamma.agentkernel.agent.AgentContext;
import com.gamma.agentkernel.agent.CapabilityRegistry;
import com.gamma.agentkernel.eval.EvalCase;
import com.gamma.agentkernel.eval.EvalCaseLoader;
import com.gamma.agentkernel.eval.Evals;
import com.gamma.agentkernel.eval.FakeModelProvider;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.retrieve.DocRetriever;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The golden-eval net (Gap E), now running on the kernel's {@code agent-eval} harness (U1.6). The same
 * declarative JSON fixtures that pinned 3.x behavior in U0 are loaded by {@link EvalCaseLoader} and run
 * through {@link Evals#asTests} against the reshaped 4.0 {@code Capability} SPI — direct proof the kernel
 * migration preserved observable behavior. Confidence is now numeric, so the fixtures assert
 * {@code minConfidence}.
 *
 * <p><b>How the model is supplied.</b> The kernel {@link EvalCase} carries no per-case model block, so
 * model scripting lives here: each intent's {@code cases.json} (model-available) runs against a context
 * backed by a per-intent canned {@link FakeModelProvider}; each {@code cases-nomodel.json} runs against a
 * context with <em>no</em> available provider ({@code ModelRouter.of(Map.of())}) — the deterministic way
 * to exercise the abstain-when-no-model path (and {@code report-narrative}'s template fallback) now that
 * the kernel fake is always-available. The kernel {@link com.gamma.agentkernel.eval.EvalRunner} dispatches
 * the capability directly, so {@code minConfidence} pins each capability's self-reported confidence; the
 * estimator/abstain gate stays covered by the {@code UccAssistAgent}-level tests.
 */
class GoldenEvalTest {

    /** Intents whose happy path needs a model; {@code _cross} (unknown-intent) is model-agnostic. */
    private static final List<String> WITH_MODEL = List.of(
            "explain-entity", "kpi-to-sql", "nl-to-schedule", "suggest-config",
            "report-sql", "report-narrative", "diagnose-and-alert", "_cross");

    /** Intents with a {@code cases-nomodel.json} (abstain, or report-narrative's template fallback). */
    private static final List<String> NO_MODEL = List.of(
            "explain-entity", "kpi-to-sql", "nl-to-schedule", "suggest-config",
            "report-sql", "report-narrative", "diagnose-and-alert");

    /** Per-intent canned model response for the model-available suites (formerly the fixture {@code model} block). */
    private static final Map<String, String> MODEL = Map.ofEntries(
            Map.entry("explain-entity", "The mini table holds mini events."),
            Map.entry("kpi-to-sql", """
                {"sql":"SELECT COUNT(*) AS n FROM mini","logicExplanation":"count the mini events",\
                "chosenJoinKeys":[],"kpiInterpretation":"total number of mini events",\
                "enrichmentConfigSnippet":"SELECT COUNT(*) AS n FROM mini"}"""),
            Map.entry("nl-to-schedule",
                    "{\"name\":\"weekday-report\",\"cron\":\"0 6 * * MON-FRI\",\"job_type\":\"report\"}"),
            Map.entry("suggest-config", """
                {"fields":[{"name":"job.name","value":"nightly","rationale":"derived","confidence":"high"},\
                {"name":"job.cron","value":"0 2 * * *","rationale":"nightly window","confidence":"high"},\
                {"name":"job.type","value":"maintenance","rationale":"cleanup","confidence":"medium"}]}"""),
            Map.entry("report-sql", """
                {"sql":"SELECT status, COUNT(*) AS n FROM batches GROUP BY status",\
                "logicExplanation":"count batches by terminal status"}"""),
            Map.entry("report-narrative", "No batches have run yet: 0 total, 0 succeeded, 0 failed."),
            Map.entry("diagnose-and-alert", """
                {"name":"high-error-rate","metric":"error_rate","comparator":"gt","threshold":0.05,\
                "window":"1h","severity":"CRITICAL"}"""),
            Map.entry("_cross", ""));

    private static Path dir;
    private static SourceService svc;
    private static CapabilityRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        dir = Files.createTempDirectory("ucc-eval");
        Path pipe = AgentTestConfigs.writePipeline(dir);
        Files.createDirectories(dir.resolve("nodocs"));     // empty docs corpus → deterministic grounding
        seedEventPartition(dir);
        svc = new SourceService(List.of(pipe), 60, 1);
        registry = CapabilityRegistry.of(List.of(
                new ExplainEntitySkill(),
                new NlToScheduleSkill(),
                new SuggestConfigSkill(),
                new KpiToSqlSkill(),
                new DiagnoseAndAlertSkill(),
                new ReportSqlSkill(),
                new ReportNarrativeSkill()));
    }

    @AfterAll
    static void tearDown() {
        if (svc != null) svc.close();
        deleteRecursively(dir);
    }

    /** A read-only UCC context over the shared mini pipeline, backed by the given model router. */
    private static AgentContext ctx(ModelRouter router) {
        return new UccAgentContext(svc.catalog(), svc.reports(), svc.statusStore(),
                DocRetriever.fromDir(dir.resolve("nodocs")), router, svc.configSource());
    }

    @TestFactory
    Stream<DynamicTest> modelAvailable() {
        return WITH_MODEL.stream().flatMap(intent -> {
            List<EvalCase> cases = EvalCaseLoader.fromResource("/eval/" + intent + "/cases.json");
            ModelRouter router = ModelRouter.of(FakeModelProvider.always(MODEL.getOrDefault(intent, "")));
            return Evals.asTests(registry, ctx(router), cases);
        });
    }

    @TestFactory
    Stream<DynamicTest> modelUnavailable() {
        AgentContext ctx = ctx(ModelRouter.of(Map.of()));   // no provider for any tier → unavailable
        return NO_MODEL.stream().flatMap(intent ->
                Evals.asTests(registry, ctx,
                        EvalCaseLoader.fromResource("/eval/" + intent + "/cases-nomodel.json")));
    }

    /** The mini pipeline's Stage-1 output (CSV) lives under {@code <dir>/db}; seed one hive partition. */
    private static void seedEventPartition(Path dir) throws Exception {
        Path part = dir.resolve("db").resolve("EVENT_DATE=2026-01-01");
        Files.createDirectories(part);
        Files.writeString(part.resolve("part-0.csv"), "id,amt\n1,10\n2,20\n3,30\n");
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        } catch (Exception ignored) { /* best-effort temp cleanup */ }
    }
}
