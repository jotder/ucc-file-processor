package com.gamma.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agentkernel.agent.AgentResult;
import com.gamma.agentkernel.model.ModelRouter;
import com.gamma.agentkernel.observe.AgentCompleted;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.control.ControlApi;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for the {@code kpi-to-sql} hero skill (M6): the real {@link UccAssistAgent} wired
 * into a live {@link ControlApi}, over HTTP, CPU-only via a {@link FakeModelProvider}. The catalog's
 * {@code event:mini_etl/mini} table is backed by a real seeded CSV partition so the SQL sandbox oracle
 * can actually {@code EXPLAIN} the generated query. Proves: a validated draft is returned (draft-only,
 * scoped), and a model-suggested file-reading query is never surfaced over the wire (gap G4).
 */
class KpiToSqlEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String GOOD_SQL =
            "{\"sql\":\"SELECT COUNT(*) AS n FROM mini\","
                    + "\"logicExplanation\":\"count the mini events\","
                    + "\"chosenJoinKeys\":[],"
                    + "\"kpiInterpretation\":\"total number of mini events\","
                    + "\"enrichmentConfigSnippet\":\"SELECT COUNT(*) AS n FROM mini\"}";

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Open the service over the mini pipeline and seed a real CSV partition under its db root. */
    private Ctx open(Path dir, ModelRouter router) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        seedEventPartition(dir);
        SourceService svc = new SourceService(List.of(pipe), 60, 1);
        svc.registerAgent(new UccAssistAgent(router));
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** The mini pipeline's Stage-1 output (CSV) lives under {@code <dir>/db}; seed one hive partition. */
    private static void seedEventPartition(Path dir) throws Exception {
        Path part = dir.resolve("db").resolve("EVENT_DATE=2026-01-01");
        Files.createDirectories(part);
        Files.writeString(part.resolve("part-0.csv"), "id,amt\n1,10\n2,20\n3,30\n");
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void kpiToSqlEndToEndReturnsValidatedDraft(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, ModelRouter.of(FakeModelProvider.canned(GOOD_SQL)))) {
            String body = """
                    {"screenContext":{"kpiDescription":"count of mini events",
                     "catalogRefs":["event:mini_etl/mini"]}}""";
            HttpResponse<String> r = post(c.port, "/assist/kpi-to-sql", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("kpi-to-sql", out.get("intent").asText());
            assertEquals("OK", out.get("status").asText());
            assertTrue(out.get("validated").asBoolean(), "the SQL planned in the sandbox oracle");
            assertTrue(out.get("applyVia").isNull(), "draft-only (V-9): no write endpoint");

            JsonNode data = out.get("data");
            assertEquals("SELECT COUNT(*) AS n FROM mini", data.get("sql").asText());
            assertEquals("n", data.get("columnsProduced").get(0).asText(),
                    "columns are authoritative, from the oracle");
            boolean citesEvent = false;
            for (JsonNode cit : out.get("citations"))
                if (cit.get("ref").asText().equals("event:mini_etl/mini")) citesEvent = true;
            assertTrue(citesEvent, "cites the grounded catalog node: " + out.get("citations"));
        }
    }

    @Test
    void unsafeQueryIsNeverSurfacedOverHttp(@TempDir Path dir) throws Exception {
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "{\"sql\":\"SELECT * FROM read_csv('/etc/passwd')\"}"));
        try (Ctx c = open(dir, router)) {
            String body = """
                    {"screenContext":{"kpiDescription":"x","catalogRefs":["event:mini_etl/mini"]}}""";
            HttpResponse<String> r = post(c.port, "/assist/kpi-to-sql", body);
            assertEquals(503, r.statusCode(), "an always-unsafe model yields a graceful failure");
            assertFalse(r.body().contains("/etc/passwd"), "the attack SQL is never echoed to the client");
        }
    }

    @Test
    void kpiToSqlDraftIsAuditedAsOk(@TempDir Path dir) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        seedEventPartition(dir);
        List<AgentCompleted> captured = new CopyOnWriteArrayList<>();
        try (SourceService svc = new SourceService(List.of(pipe), 60, 1)) {
            UccAssistAgent agent = new UccAssistAgent(
                    ModelRouter.of(FakeModelProvider.canned(GOOD_SQL)), e -> captured.add((AgentCompleted) e));
            agent.init(svc);

            AssistResult res = agent.assist(new AssistRequest("kpi-to-sql",
                    Map.of("kpiDescription", "count of mini events",
                            "catalogRefs", List.of("event:mini_etl/mini")), Map.of(), null));
            assertEquals(AssistResult.Status.OK, res.status(), res.message());
            assertNull(res.applyVia(), "draft-only: the audited call carries no write endpoint");

            assertEquals(1, captured.size(), "exactly one suggestion event for the draft");
            AgentCompleted e = captured.get(0);
            assertEquals("kpi-to-sql", e.capabilityId());
            assertEquals(AgentResult.Status.OK, e.status());
            assertTrue(e.contextKeys().contains("kpiDescription"), "context keys recorded (not values)");
        }
    }
}
