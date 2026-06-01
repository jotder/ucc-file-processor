package com.gamma.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.model.ModelRouter;
import com.gamma.control.ControlApi;
import com.gamma.etl.BatchEvent;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2 end-to-end test: the real {@link UccAssistAgent} (agent module) wired into a live
 * {@link ControlApi} (core module), exercised over HTTP. Runs CPU-only — the agent's model router
 * is backed by a {@link FakeModelProvider}, so no Ollama is needed. This is the test that can only
 * live in the agent module, where both core and agent are on the classpath.
 */
class AssistEndToEndTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, ModelRouter router) throws Exception {
        Path pipe = AgentTestConfigs.writePipeline(dir);
        SourceService svc = new SourceService(List.of(pipe), 60, 1);
        svc.registerAgent(new UccAssistAgent(router));   // bypass ServiceLoader; inject the fake
        ControlApi api = new ControlApi(svc, 0, TOKEN);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> post(int port, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    @Test
    void explainEntityEndToEndOverHttp(@TempDir Path dir) throws Exception {
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned("The mini table holds mini events."));
        try (Ctx c = open(dir, router)) {
            String body = """
                    {"screenContext":{"entityType":"table","id":"event:mini_etl/mini"},
                     "userText":"what does this table contain?"}""";
            HttpResponse<String> r = post(c.port, "/assist/explain-entity", TOKEN, body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("explain-entity", out.get("intent").asText());
            assertEquals("OK", out.get("status").asText());
            assertEquals("The mini table holds mini events.", out.get("answer").asText());
            assertTrue(out.get("applyVia").isNull(), "read-only — no write endpoint");
            boolean citesEvent = false;
            for (JsonNode c2 : out.get("citations"))
                if (c2.get("ref").asText().equals("event:mini_etl/mini")) citesEvent = true;
            assertTrue(citesEvent, "cites the catalog node: " + out.get("citations"));
        }
    }

    @Test
    void nlToScheduleEndToEndReturnsDraftPayload(@TempDir Path dir) throws Exception {
        // The fake model returns a JSON schedule for every tier; the skill validates it via the
        // cron + job oracle and returns a draft — no Ollama, no write.
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "{\"name\":\"weekday-report\",\"cron\":\"0 6 * * MON-FRI\",\"job_type\":\"report\"}"));
        try (Ctx c = open(dir, router)) {
            String body = "{\"userText\":\"every weekday at 6am\"}";
            HttpResponse<String> r = post(c.port, "/assist/nl-to-schedule", TOKEN, body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("nl-to-schedule", out.get("intent").asText());
            assertEquals("OK", out.get("status").asText());
            assertTrue(out.get("validated").asBoolean(), "ran through the oracle");
            assertTrue(out.get("applyVia").isNull(), "draft-only (V-9): no write endpoint, ever");

            JsonNode data = out.get("data");
            assertEquals("0 6 * * MON-FRI", data.get("cron").asText());
            assertEquals("every day at 06:00 on weekdays", data.get("humanReadable").asText());
            assertEquals(5, data.get("nextRuns").size());
            assertTrue(data.get("draftToon").asText().contains("0 6 * * MON-FRI"),
                    "the saveable draft .toon rides in the structured payload");
        }
    }

    @Test
    void nlToScheduleRouteIsScopedAndDoesNotWrite(@TempDir Path dir) throws Exception {
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "{\"name\":\"j\",\"cron\":\"0 2 * * *\",\"job_type\":\"maintenance\"}"));
        try (Ctx c = open(dir, router)) {
            // Fail-closed: no/!valid token is rejected before the agent is ever consulted.
            assertEquals(401, post(c.port, "/assist/nl-to-schedule", null, "{\"userText\":\"daily 2am\"}").statusCode());
            assertEquals(401, post(c.port, "/assist/nl-to-schedule", "wrong", "{\"userText\":\"daily 2am\"}").statusCode());
            // With the scoped token it answers, but nothing is persisted — the suggestion is a draft.
            HttpResponse<String> ok = post(c.port, "/assist/nl-to-schedule", TOKEN, "{\"userText\":\"daily 2am\"}");
            assertEquals(200, ok.statusCode(), ok.body());
            int jobCount = c.svc.jobService().map(js -> js.jobs().size()).orElse(0);
            assertEquals(0, jobCount, "draft-only: the suggestion is a draft, no job was created");
        }
    }

    @Test
    void suggestConfigEndToEndReturnsDraftPayload(@TempDir Path dir) throws Exception {
        // 'job' has no filesystem surface, so the safety gate is a no-op regardless of policy roots —
        // this exercises the full HTTP → agent → spec+safety oracle → draft path CPU-only.
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned("""
                {"fields":[
                   {"name":"job.name","value":"nightly","rationale":"derived","confidence":"high"},
                   {"name":"job.cron","value":"0 2 * * *","rationale":"nightly window","confidence":"high"},
                   {"name":"job.type","value":"maintenance","rationale":"cleanup","confidence":"medium"}
                ]}"""));
        try (Ctx c = open(dir, router)) {
            assertEquals(401, post(c.port, "/assist/suggest-config", null,
                    "{\"screenContext\":{\"configType\":\"job\"}}").statusCode(), "fail-closed");

            HttpResponse<String> r = post(c.port, "/assist/suggest-config", TOKEN,
                    "{\"screenContext\":{\"configType\":\"job\"}}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("suggest-config", out.get("intent").asText());
            assertTrue(out.get("applyVia").isNull(), "draft-only (V-9): no write endpoint");
            JsonNode data = out.get("data");
            assertEquals("job", data.get("configType").asText());
            assertEquals(Boolean.TRUE, data.get("safetyChecked").asBoolean());
            assertEquals(3, data.get("fields").size());
            assertTrue(data.get("draftToon").asText().contains("0 2 * * *"),
                    "the saveable draft .toon rides in the structured payload");
        }
    }

    @Test
    void diagnoseAndAlertEndToEndReturnsDraftPayload(@TempDir Path dir) throws Exception {
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "{\"name\":\"high-error-rate\",\"metric\":\"error_rate\",\"comparator\":\"gt\","
                        + "\"threshold\":0.05,\"window\":\"1h\",\"severity\":\"CRITICAL\"}"));
        try (Ctx c = open(dir, router)) {
            assertEquals(401, post(c.port, "/assist/diagnose-and-alert", null,
                    "{\"userText\":\"warn at 5%\"}").statusCode(), "fail-closed");

            HttpResponse<String> r = post(c.port, "/assist/diagnose-and-alert", TOKEN,
                    "{\"userText\":\"warn when the error rate exceeds 5%\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("diagnose-and-alert", out.get("intent").asText());
            assertTrue(out.get("validated").asBoolean(), "ran through the alert-rule oracle");
            assertTrue(out.get("applyVia").isNull(), "draft-only (V-9): no write endpoint, ever");
            JsonNode data = out.get("data");
            assertEquals("error_rate", data.get("metric").asText());
            assertEquals("CRITICAL", data.get("severity").asText());
            assertTrue(data.get("draftToon").asText().contains("error_rate"),
                    "the saveable draft .toon rides in the structured payload");
        }
    }

    @Test
    void diagnosesEndpointReflectsAFailedBatch(@TempDir Path dir) throws Exception {
        // The model enriches the prose; the deterministic heuristic still sets severity. Driving a
        // FAILED event through the real bus exercises the full event → reactor → store → HTTP path.
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "Input columns no longer match the configured schema; reconcile the selectors."));
        try (Ctx c = open(dir, router)) {
            assertEquals(401, get(c.port, "/assist/diagnoses", null).statusCode(), "fail-closed");
            assertEquals(200, get(c.port, "/assist/diagnoses", TOKEN).statusCode());
            assertEquals(0, JSON.readTree(get(c.port, "/assist/diagnoses", TOKEN).body()).size(),
                    "no failures yet");

            c.svc.eventBus().publish(new BatchEvent("MINI_ETL", "B1", "FAILED", List.of(),
                    0, 10L, 1, "schema selector mismatch", "bad.csv", 3));

            // Diagnosis runs off-thread; poll the endpoint until it lands.
            JsonNode arr = null;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                arr = JSON.readTree(get(c.port, "/assist/diagnoses", TOKEN).body());
                if (arr.size() > 0) break;
                Thread.sleep(20);
            }
            assertNotNull(arr);
            assertEquals(1, arr.size(), "the failed batch produced one diagnosis");
            JsonNode d = arr.get(0);
            assertEquals("B1", d.get("batchId").asText());
            assertEquals("CRITICAL", d.get("severity").asText(), "no output on a failure -> critical");
            assertFalse(d.get("heuristicOnly").asBoolean(), "the fake model enriched the root cause");
            assertTrue(d.get("rootCause").asText().toLowerCase().contains("schema"));
        }
    }

    @Test
    void reportSqlEndToEndReturnsDraftPayload(@TempDir Path dir) throws Exception {
        // The fake model returns a read-only query over the operational tables; the skill validates it
        // in the sealed SQL sandbox and returns a draft. The real (empty) status store still presents
        // correctly-shaped tables, so the query plans CPU-only — no Ollama, no write.
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "{\"sql\":\"SELECT status, COUNT(*) AS n FROM batches GROUP BY status\","
                        + "\"logicExplanation\":\"count batches by terminal status\"}"));
        try (Ctx c = open(dir, router)) {
            assertEquals(401, post(c.port, "/assist/report-sql", null,
                    "{\"screenContext\":{\"pipeline\":\"MINI_ETL\"}}").statusCode(), "fail-closed");

            String body = """
                    {"screenContext":{"pipeline":"MINI_ETL"},
                     "userText":"how many batches per status?"}""";
            HttpResponse<String> r = post(c.port, "/assist/report-sql", TOKEN, body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("report-sql", out.get("intent").asText());
            assertTrue(out.get("validated").asBoolean(), "ran through the SQL sandbox oracle");
            assertTrue(out.get("applyVia").isNull(), "draft-only (V-9): no write endpoint, ever");
            JsonNode data = out.get("data");
            assertEquals("status", data.get("columnsProduced").get(0).asText(),
                    "columns are authoritative, from the oracle");
            boolean usesBatches = false;
            for (JsonNode t : data.get("tablesUsed")) if (t.asText().equals("batches")) usesBatches = true;
            assertTrue(usesBatches, "queried the resolved operational table: " + data.get("tablesUsed"));
        }
    }

    @Test
    void reportNarrativeEndToEndReturnsGroundedNarrative(@TempDir Path dir) throws Exception {
        // A fresh service has run nothing → the service report is all zeros; the canned narrative uses
        // only those grounded figures, so the extractive guard passes.
        ModelRouter router = ModelRouter.of(FakeModelProvider.canned(
                "No batches have run yet: 0 total, 0 succeeded, 0 failed."));
        try (Ctx c = open(dir, router)) {
            assertEquals(401, post(c.port, "/assist/report-narrative", null,
                    "{\"screenContext\":{\"reportType\":\"service\"}}").statusCode(), "fail-closed");

            HttpResponse<String> r = post(c.port, "/assist/report-narrative", TOKEN,
                    "{\"screenContext\":{\"reportType\":\"service\"}}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("report-narrative", out.get("intent").asText());
            assertTrue(out.get("applyVia").isNull(), "draft-only");
            JsonNode data = out.get("data");
            assertEquals("service", data.get("reportType").asText());
            assertEquals(Boolean.TRUE, data.get("grounded").asBoolean());
            assertTrue(data.get("narrative").asText().contains("0 total"),
                    "the grounded narrative rides in the structured payload");
        }
    }

    @Test
    void scopedAuthEnforced(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, ModelRouter.of(FakeModelProvider.canned("ok")))) {
            assertEquals(401, post(c.port, "/assist/explain-entity", null, "{}").statusCode());
            assertEquals(401, post(c.port, "/assist/explain-entity", "wrong", "{}").statusCode());
        }
    }

    @Test
    void unknownIntentIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, ModelRouter.of(FakeModelProvider.canned("ok")))) {
            assertEquals(404, post(c.port, "/assist/no-such-skill", TOKEN, "{}").statusCode());
        }
    }

    @Test
    void modelUnavailableIs503(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, ModelRouter.of(FakeModelProvider.down()))) {
            String body = "{\"screenContext\":{\"id\":\"event:mini_etl/mini\"},\"userText\":\"explain\"}";
            HttpResponse<String> r = post(c.port, "/assist/explain-entity", TOKEN, body);
            assertEquals(503, r.statusCode(), r.body());
        }
    }
}
