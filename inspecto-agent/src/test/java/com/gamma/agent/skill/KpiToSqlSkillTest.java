package com.gamma.agent.skill;

import com.gamma.agentkernel.model.ModelRequest;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agentkernel.agent.AgentRequest;
import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.MetadataGraphService;
import com.gamma.catalog.MetadataNode;
import com.gamma.catalog.NodeKind;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for {@code kpi-to-sql} (M6), CPU-only via a {@link FakeModelProvider} and a temp-rooted
 * {@link SqlSandboxPolicy}. The catalog is hand-built over a seeded CSV "reference table" so the oracle
 * validates against real partitions. The point of the milestone is proven here: a model-suggested
 * file-reading query is rejected by the sandbox and repaired — never surfaced; a clean draft is
 * confirm-first (interpretation + join keys), draft-only (no applyVia), with authoritative columns.
 */
class KpiToSqlSkillTest {

    private static final SqlSandboxPolicy POLICY = SqlSandboxPolicy.withCaps("512MB", 1, 10);

    private final KpiToSqlSkill skill = new KpiToSqlSkill(POLICY);

    // ── hand-built catalog over a seeded CSV reference table + a KPI ────────────────────

    private record Cat(MetadataGraphService catalog, String refId, String kpiId) {}

    private static Cat catalog(Path dir) throws IOException {
        Path csv = dir.resolve("events.csv");
        Files.writeString(csv, "region,dur\nnorth,10\nnorth,20\nsouth,30\n");
        String csvPath = csv.toAbsolutePath().toString().replace('\\', '/');

        EnrichmentConfig en = new EnrichmentConfig("DAILY",
                new EnrichmentConfig.Input("database/x", "PARQUET", List.of("year")),
                List.of(new EnrichmentConfig.Reference("events", csvPath, "CSV")),
                new EnrichmentConfig.Output("reports/x", "PARQUET", "snappy", List.of("year")),
                "SELECT 1",
                new EnrichmentConfig.Triggers(null, 0L));

        var kpi = new com.gamma.catalog.SemanticModel.KpiMeta("calls_by_region",
                "Total calls per region", "region", List.of("events"), List.of("region"));
        var sem = new com.gamma.catalog.SemanticModel("sem",
                Map.of(), Map.of("calls_by_region", kpi), Map.of(),
                new com.gamma.catalog.SemanticModel.DomainNotes("USD", "UTC", List.of()));

        ConfigSource cs = new ConfigSource() {
            public List<PipelineConfig> pipelines() { return List.of(); }
            public List<EnrichmentConfig> enrichments() { return List.of(en); }
            public List<com.gamma.catalog.SemanticModel> semantics() { return List.of(sem); }
        };
        MetadataGraphService svc = new MetadataGraphService(cs, null, List.of());
        String refId = svc.nodesOfKind(NodeKind.REFERENCE_DATASET).get(0).id();
        String kpiId = svc.nodesOfKind(NodeKind.KPI).get(0).id();
        return new Cat(svc, refId, kpiId);
    }

    private static UccAgentContext ctx(MetadataGraphService catalog, ModelRouter router) {
        return new UccAgentContext(catalog, null, null, null, router, null);
    }

    private static AgentRequest ask(Cat cat, boolean sampleRows) {
        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("kpiDescription", "total calls per region");
        partial.put("targetGrain", "region");
        partial.put("catalogRefs", List.of(cat.refId, cat.kpiId));
        if (sampleRows) partial.put("sampleRows", true);
        return new AgentRequest(KpiToSqlSkill.ID, Map.of(), partial, "total calls per region");
    }

    private static final String GOOD_SQL =
            "{\"sql\":\"SELECT region, COUNT(*) AS calls FROM events GROUP BY region\","
                    + "\"logicExplanation\":\"group the events by region and count\","
                    + "\"columnsProduced\":[\"region\",\"calls\"],"
                    + "\"chosenJoinKeys\":[\"region\"],"
                    + "\"kpiInterpretation\":\"number of call events per serving region\","
                    + "\"enrichmentConfigSnippet\":\"SELECT region, COUNT(*) AS calls FROM events GROUP BY region\"}";

    // ── tests ──────────────────────────────────────────────────────────────────────────

    @Test
    void validKpiProducesConfirmFirstDraft(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AgentResult res = skill.run(ask(cat, false),
                ctx(cat.catalog, ModelRouter.of(FakeModelProvider.canned(GOOD_SQL))));

        assertEquals(AgentResult.Status.OK, res.status(), res.message());
        assertTrue(res.validated(), "the SQL planned in the sandbox oracle");
        assertNull(res.applyVia(), "draft-only (V-9): no write endpoint");

        Map<String, Object> data = res.data();
        assertEquals("SELECT region, COUNT(*) AS calls FROM events GROUP BY region", data.get("sql"));
        assertEquals(List.of("region", "calls"), data.get("columnsProduced"),
                "columns are authoritative (from the oracle), not the model's claim");
        assertEquals(List.of("region"), data.get("chosenJoinKeys"));
        assertFalse(String.valueOf(data.get("kpiInterpretation")).isBlank(),
                "confirm-first: the interpretation is surfaced");
        assertFalse(data.containsKey("sampleRows"), "no preview rows unless requested");

        boolean citesRef = res.evidence().stream().anyMatch(c -> c.sourceRef().equals(cat.refId));
        boolean citesKpi = res.evidence().stream().anyMatch(c -> c.sourceRef().equals(cat.kpiId));
        assertTrue(citesRef && citesKpi, "grounded citations for the resolved nodes: " + res.evidence());
    }

    @Test
    void unsafeSqlIsRepairedNotSurfaced(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AtomicInteger round = new AtomicInteger();
        ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                round.incrementAndGet() == 1
                        ? "{\"sql\":\"SELECT * FROM read_csv('/etc/passwd')\"}"   // attack
                        : GOOD_SQL));                                              // corrected

        AgentResult res = skill.run(ask(cat, false), ctx(cat.catalog, router));

        assertEquals(AgentResult.Status.OK, res.status(), "the unsafe query was repaired, not failed");
        assertEquals(Boolean.TRUE, res.data().get("repaired"));
        assertEquals("SELECT region, COUNT(*) AS calls FROM events GROUP BY region", res.data().get("sql"),
                "the file-reading query is never surfaced");
        assertFalse(String.valueOf(res.data().get("sql")).contains("read_csv"));
    }

    @Test
    void persistentlyUnsafeModelFailsGracefully(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "{\"sql\":\"SELECT * FROM read_csv('/etc/passwd')\"}"));
        AgentResult res = skill.run(ask(cat, false), ctx(cat.catalog, router));

        assertEquals(AgentResult.Status.UNAVAILABLE, res.status(),
                "an always-unsafe model yields a graceful failure, never the unsafe SQL");
        assertTrue(res.data() == null || !res.data().containsKey("sql"),
                "no SQL draft is surfaced when every attempt is unsafe");
    }

    @Test
    void sampleRowsReturnedOnlyWhenRequested(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AgentResult res = skill.run(ask(cat, true),
                ctx(cat.catalog, ModelRouter.of(FakeModelProvider.canned(GOOD_SQL))));

        assertEquals(AgentResult.Status.OK, res.status(), res.message());
        Object sample = res.data().get("sampleRows");
        assertNotNull(sample, "preview rows present when sampleRows:true");
        assertTrue(sample instanceof List<?> l && !l.isEmpty(), "preview is non-empty over seeded data");
    }

    @Test
    void unknownColumnFromModelIsRepaired(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AtomicInteger round = new AtomicInteger();
        ModelRouter router = ModelRouter.of(FakeModelProvider.responding((ModelRequest r) ->
                round.incrementAndGet() == 1
                        ? "{\"sql\":\"SELECT no_such_col FROM events\"}"   // won't plan
                        : GOOD_SQL));
        AgentResult res = skill.run(ask(cat, false), ctx(cat.catalog, router));

        assertEquals(AgentResult.Status.OK, res.status(), "the unplannable query was repaired");
        assertEquals(Boolean.TRUE, res.data().get("repaired"));
    }

    @Test
    void modelUnavailableIsGraceful(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AgentResult res = skill.run(ask(cat, false),
                ctx(cat.catalog, ModelRouter.of(FakeModelProvider.down())));
        assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
    }

    @Test
    void missingKpiDescriptionIsGraceful(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AgentRequest req = new AgentRequest(KpiToSqlSkill.ID, Map.of(),
                Map.of("catalogRefs", List.of(cat.refId)), null);
        AgentResult res = skill.run(req, ctx(cat.catalog, ModelRouter.of(FakeModelProvider.canned(GOOD_SQL))));
        assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
    }

    @Test
    void noResolvableDataTableIsGraceful(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        // Only the KPI ref (grounding-only) — no data table to validate against.
        AgentRequest req = new AgentRequest(KpiToSqlSkill.ID, Map.of(),
                Map.of("kpiDescription", "x", "catalogRefs", List.of(cat.kpiId)), null);
        AgentResult res = skill.run(req, ctx(cat.catalog, ModelRouter.of(FakeModelProvider.canned(GOOD_SQL))));
        assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
    }

    @Test
    void emptyCatalogRefsIsGraceful(@TempDir Path dir) throws Exception {
        Cat cat = catalog(dir);
        AgentRequest req = new AgentRequest(KpiToSqlSkill.ID, Map.of(),
                Map.of("kpiDescription", "x"), null);
        AgentResult res = skill.run(req, ctx(cat.catalog, ModelRouter.of(FakeModelProvider.canned(GOOD_SQL))));
        assertEquals(AgentResult.Status.UNAVAILABLE, res.status());
    }
}
