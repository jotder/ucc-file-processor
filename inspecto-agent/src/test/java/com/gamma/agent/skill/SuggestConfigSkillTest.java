package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.kernel.model.ModelRequest;
import com.gamma.agent.kernel.model.ModelRouter;
import com.gamma.agent.kernel.retrieve.DocRetriever;
import com.gamma.agent.kernel.agent.AgentRequest;
import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@link SuggestConfigSkill} (M5, A3) — CPU-only via a deterministic
 * {@link FakeModelProvider}; no Ollama. Proves the spec+safety oracle, the draft-only guarantees, and
 * that an unsafe model suggestion is rejected by the safety gate (R6) and repaired, never surfaced.
 */
class SuggestConfigSkillTest {

    private SuggestConfigSkill skill(Path root) {
        return new SuggestConfigSkill(SafetyPolicy.withRoots(root));
    }

    private UccAgentContext context(CollectorService svc, ModelRouter router) {
        return new UccAgentContext(svc.catalog(), svc.reports(), svc.statusStore(),
                new DocRetriever(Map.of()), router, svc.configSource());
    }

    /** A pipeline request whose partial config already declares dirs under {@code root}. */
    private AgentRequest pipelineReq(Path root) {
        Map<String, Object> dirs = new LinkedHashMap<>();
        dirs.put("poll", root.resolve("inbox").toString().replace("\\", "/"));
        dirs.put("database", root.resolve("db").toString().replace("\\", "/"));
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("name", "ADJ");
        partial.put("dirs", dirs);
        return new AgentRequest(SuggestConfigSkill.ID,
                Map.of("configType", "pipeline", "sourceSample", "id,amt,ts\n1,2.0,2026-01-01"),
                partial, null);
    }

    @Test
    void pipelineDraftIsValidatedSafeAndRoundTrips(@TempDir Path root) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(root);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned("""
                    {"fields":[
                       {"name":"processing.threads","value":2,"rationale":"~2 cores","confidence":"high"},
                       {"name":"output.format","value":"PARQUET","rationale":"columnar","confidence":"medium"}
                    ]}"""));
            AgentResult res = skill(root).run(pipelineReq(root), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status(), res.message());
            assertTrue(res.validated());
            assertNull(res.applyVia(), "draft-only (V-9)");
            Map<String, Object> data = res.data();
            assertEquals("pipeline", data.get("configType"));
            assertEquals(Boolean.TRUE, data.get("safetyChecked"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
            assertEquals(2, fields.size());
            assertTrue(fields.get(0).containsKey("rationale") && fields.get(0).containsKey("confidence"));

            // The draft .toon round-trips and is itself spec-clean.
            String draftToon = (String) data.get("draftToon");
            Map<String, Object> reparsed = ConfigCodec.toMap(draftToon);
            List<Finding> findings = ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), reparsed);
            assertTrue(findings.stream().noneMatch(f -> f.severity() == Severity.ERROR),
                    "round-tripped draft has no ERROR findings: " + findings);
        }
    }

    @Test
    void unsafePathSuggestionIsRejectedAndRepaired(@TempDir Path root) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(root);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            String safeBackup = root.resolve("backup").toString().replace("\\", "/");
            AtomicInteger round = new AtomicInteger();
            // Round 1 suggests a backup dir OUTSIDE the workspace (the safety gate rejects it);
            // round 2 corrects it to a path under the allowed root.
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                    round.incrementAndGet() == 1
                            ? "{\"fields\":[{\"name\":\"dirs.backup\",\"value\":\"/etc/exfil\",\"rationale\":\"x\",\"confidence\":\"low\"}]}"
                            : "{\"fields\":[{\"name\":\"dirs.backup\",\"value\":\"" + safeBackup + "\",\"rationale\":\"under workspace\",\"confidence\":\"high\"}]}"));
            AgentResult res = skill(root).run(pipelineReq(root), context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status(), "the unsafe draft was repaired, not surfaced");
            assertEquals(Boolean.TRUE, res.data().get("repaired"));
            assertTrue(round.get() >= 2, "the safety rejection forced a repair round");
            assertTrue(((String) res.data().get("draftToon")).contains("backup"));
        }
    }

    @Test
    void enrichmentDraftGroundsAndCitesKnownTable(@TempDir Path root) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(root);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            MetadataNode source = svc.catalog().nodesOfKind(NodeKind.STREAM).get(0);
            String tableName = source.label();
            String tableId = source.id();

            Map<String, Object> in = new LinkedHashMap<>();
            in.put("database", root.resolve("events").toString().replace("\\", "/"));
            in.put("format", "PARQUET");
            in.put("partitions", List.of("year", "month"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("database", root.resolve("out").toString().replace("\\", "/"));
            out.put("format", "PARQUET");
            out.put("partitions", List.of("year", "month"));
            Map<String, Object> partial = new LinkedHashMap<>();
            partial.put("name", "KPI");
            partial.put("input", in);
            partial.put("output", out);
            partial.put("transform", "SELECT 1");
            AgentRequest req = new AgentRequest(SuggestConfigSkill.ID,
                    Map.of("configType", "enrichment"), partial, null);

            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                    "{\"fields\":[{\"name\":\"triggers.on_pipeline\",\"value\":\"" + tableName
                            + "\",\"rationale\":\"run after upstream\",\"confidence\":\"high\"}]}"));
            AgentResult res = skill(root).run(req, context(svc, router));

            assertEquals(AgentResult.Status.OK, res.status(), res.message());
            assertTrue(res.evidence().stream()
                            .anyMatch(c -> c.effectiveTierLabel().equals("catalog") && c.sourceRef().equals(tableId)),
                    "cites the grounded table node: " + res.evidence());
        }
    }

    @Test
    void missingConfigTypeIsRejectedCleanly(@TempDir Path root) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(root);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned("{\"fields\":[]}"));
            AgentResult res = skill(root).run(
                    new AgentRequest(SuggestConfigSkill.ID, Map.of(), Map.of(), null), context(svc, router));
            assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
            assertTrue(res.message().contains("configType"));
        }
    }

    @Test
    void modelUnavailableYieldsUnavailable(@TempDir Path root) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(root);
        try (CollectorService svc = new CollectorService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.down());
            AgentResult res = skill(root).run(pipelineReq(root), context(svc, router));
            assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
            assertNull(res.answer());
        }
    }
}
