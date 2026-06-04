package com.gamma.agent.skill;

import com.gamma.agent.AgentTestConfigs;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.etl.PipelineConfig;
import com.gamma.service.SourceService;
import com.gamma.service.StatusStore;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@code report-sql} (M8), CPU-only via a {@link FakeModelProvider} and a temp-rooted
 * {@link SqlSandboxPolicy}. The pipeline is resolved through a real {@link SourceService}'s read-only
 * {@code configSource()}; the operational rows come from a stub {@link StatusStore} so the test controls
 * the data. The milestone's point is proven: a model-suggested file-reading query is rejected by the
 * sandbox and repaired — never surfaced; a clean draft is draft-only with authoritative columns.
 */
class ReportSqlSkillTest {

    private static final SqlSandboxPolicy POLICY = SqlSandboxPolicy.withCaps("512MB", 1, 10);

    private final ReportSqlSkill skill = new ReportSqlSkill(POLICY);

    private static final String GOOD =
            "{\"sql\":\"SELECT status, COUNT(*) AS n FROM batches GROUP BY status\","
                    + "\"logicExplanation\":\"count batches grouped by terminal status\"}";

    // ── fixtures ────────────────────────────────────────────────────────────────────────

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static List<Map<String, String>> twoBatches() {
        return List.of(
                row("batch_id", "b1", "pipeline", "MINI_ETL", "status", "SUCCESS",
                        "total_output_rows", "100", "start_time", "2026-06-01 10:00:00"),
                row("batch_id", "b2", "pipeline", "MINI_ETL", "status", "FAILED",
                        "total_output_rows", "0", "start_time", "2026-06-01 11:00:00"));
    }

    /** A status store that returns canned batch rows and empty everything else. */
    private static StatusStore store(List<Map<String, String>> batches) {
        return new StatusStore() {
            public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            public List<Map<String, String>> batches(PipelineConfig cfg) { return batches; }
            public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            public List<Map<String, String>> lineage(PipelineConfig cfg, String batchId) { return List.of(); }
            public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }

    private AssistContext ctx(SourceService svc, StatusStore store, ModelRouter router) {
        return new AssistContext(svc.catalog(), svc.reports(), store,
                new DocRetriever(Map.of()), router, svc.configSource());
    }

    private static AssistRequest ask(String pipeline, String question, boolean sample) {
        Map<String, Object> partial = new LinkedHashMap<>();
        if (pipeline != null) partial.put("pipeline", pipeline);
        if (sample) partial.put("sampleRows", true);
        return new AssistRequest(ReportSqlSkill.ID, Map.of(), partial, question);
    }

    // ── tests ──────────────────────────────────────────────────────────────────────────

    @Test
    void validQuestionProducesDraftWithAuthoritativeColumns(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AssistResult res = skill.run(ask("MINI_ETL", "how many batches per status?", false),
                    ctx(svc, store(twoBatches()), ModelRouter.of(FakeModelProvider.canned(GOOD))));

            assertEquals(AssistResult.Status.OK, res.status(), res.message());
            assertTrue(res.validated(), "the SQL planned in the sandbox oracle");
            assertNull(res.applyVia(), "draft-only (V-9): no write endpoint");
            assertEquals(List.of("status", "n"), res.data().get("columnsProduced"),
                    "columns are authoritative (from the oracle)");
            assertTrue(((List<?>) res.data().get("tablesUsed")).contains("batches"));
            assertFalse(res.data().containsKey("sampleRows"), "no preview rows unless requested");
            assertTrue(res.citations().stream().anyMatch(c -> c.ref().equalsIgnoreCase("MINI_ETL")),
                    "grounded citation for the resolved pipeline: " + res.citations());
        }
    }

    @Test
    void sampleRowsReturnedOnlyWhenRequested(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AssistResult res = skill.run(ask("MINI_ETL", "batches per status", true),
                    ctx(svc, store(twoBatches()), ModelRouter.of(FakeModelProvider.canned(GOOD))));

            assertEquals(AssistResult.Status.OK, res.status(), res.message());
            Object sample = res.data().get("sampleRows");
            assertTrue(sample instanceof List<?> l && !l.isEmpty(), "preview present over the seeded rows");
        }
    }

    @Test
    void fileReadingQueryIsRepairedNotSurfaced(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AtomicInteger round = new AtomicInteger();
            ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                    round.incrementAndGet() == 1
                            ? "{\"sql\":\"SELECT * FROM read_csv('/etc/passwd')\"}"   // attack
                            : GOOD));                                                 // corrected
            AssistResult res = skill.run(ask("MINI_ETL", "how many batches", false),
                    ctx(svc, store(twoBatches()), router));

            assertEquals(AssistResult.Status.OK, res.status(), "the unsafe query was repaired, not failed");
            assertEquals(Boolean.TRUE, res.data().get("repaired"));
            assertFalse(String.valueOf(res.data().get("sql")).contains("read_csv"),
                    "the file-reading query is never surfaced");
        }
    }

    @Test
    void persistentlyUnsafeModelFailsGracefully(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                    "{\"sql\":\"SELECT * FROM read_csv('/etc/passwd')\"}"));
            AssistResult res = skill.run(ask("MINI_ETL", "x", false), ctx(svc, store(twoBatches()), router));

            assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
            assertTrue(res.data() == null || !res.data().containsKey("sql"),
                    "no SQL draft surfaced when every attempt is unsafe");
        }
    }

    @Test
    void unknownPipelineIsGrounded(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AssistResult res = skill.run(ask("NOPE", "how many batches", false),
                    ctx(svc, store(twoBatches()), ModelRouter.of(FakeModelProvider.canned(GOOD))));
            assertEquals(AssistResult.Status.UNAVAILABLE, res.status(),
                    "a pipeline that does not resolve cannot be queried (grounding)");
        }
    }

    @Test
    void noPipelineOrJobIsGraceful(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AssistResult res = skill.run(ask(null, "how many batches", false),
                    ctx(svc, store(twoBatches()), ModelRouter.of(FakeModelProvider.canned(GOOD))));
            assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
        }
    }

    @Test
    void modelUnavailableIsGraceful(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            AssistResult res = skill.run(ask("MINI_ETL", "how many batches", false),
                    ctx(svc, store(twoBatches()), ModelRouter.of(FakeModelProvider.down())));
            assertEquals(AssistResult.Status.UNAVAILABLE, res.status());
        }
    }
}
