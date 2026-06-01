package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.assist.AssistRequest;
import com.gamma.assist.AssistResult;
import com.gamma.assist.Diagnosis;
import com.gamma.assist.spi.AssistAgent;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the v3.3.0 assist route over real HTTP (P0): {@code POST /assist/{intent}}
 * (scope assist.read) delegating to the in-process {@link AssistAgent}. Core holds only the seam —
 * these tests register a tiny stub agent (the SPI lives in core) to exercise the status mapping:
 * no agent → 503, unknown intent → 404, model-unavailable → 503, OK → 200 with the result body.
 */
class ControlApiAssistTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** A minimal in-core agent: answers {@code echo}, reports {@code down} unavailable, else unsupported. */
    private static final class StubAgent implements AssistAgent {
        @Override public String name() { return "stub"; }
        @Override public void init(SourceService service) { /* no handles needed */ }
        @Override public AssistResult assist(AssistRequest req) {
            return switch (req.intent()) {
                case "echo" -> AssistResult.answer("echo", "you said: " + req.userText(),
                        List.of(new AssistResult.Citation("test", "node:1")), List.of("http://x/1"));
                case "down" -> AssistResult.unavailable("down", "model offline");
                case "draft" -> AssistResult.draft("draft", "every weekday at 06:00",
                        List.of(new AssistResult.Citation("catalog", "source:adjustment_etl")), List.of(),
                        Map.of("cron", "0 6 * * MON-FRI",
                               "onPipeline", "adjustment_etl",
                               "nextRuns", List.of("2026-06-01 06:00:00", "2026-06-02 06:00:00"),
                               "draftToon", "job:\n  name: nightly\n  cron: \"0 6 * * MON-FRI\"\n"));
                default -> AssistResult.unsupported(req.intent());
            };
        }
        @Override public List<Diagnosis> recentDiagnoses(int limit) {
            return limit <= 0 ? List.of() : List.of(new Diagnosis(
                    "B7", "mini_etl", Diagnosis.Severity.CRITICAL,
                    "all member files rejected: schema selector mismatch",
                    null, true, 1_000L,
                    List.of(new AssistResult.Citation("catalog", "source:mini_etl"))));
        }
    }

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Open a service+API; when {@code withAgent}, register the stub agent before serving. */
    private Ctx open(Path dir, boolean withAgent) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        SourceService svc = new SourceService(List.of(pipe), 3600, 1);
        if (withAgent) svc.registerAgent(new StubAgent());
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
    void assistRouteIsScopedAssistRead(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            String body = "{\"userText\":\"hi\"}";
            assertEquals(401, post(c.port, "/assist/echo", null, body).statusCode(), "no token -> locked");
            assertEquals(401, post(c.port, "/assist/echo", "wrong", body).statusCode(), "bad token -> 401");
            assertEquals(200, post(c.port, "/assist/echo", TOKEN, body).statusCode(),
                    "control token satisfies assist.read");
        }
    }

    @Test
    void noAgentRegisteredReturns503(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> r = post(c.port, "/assist/echo", TOKEN, "{\"userText\":\"hi\"}");
            assertEquals(503, r.statusCode(), "auth passes, but no agent on the classpath");
            assertTrue(JSON.readTree(r.body()).get("error").asText().contains("not available"));
        }
    }

    @Test
    void okIntentReturnsResultBody(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/echo", TOKEN, "{\"userText\":\"hello\"}");
            assertEquals(200, r.statusCode());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("echo", out.get("intent").asText());
            assertEquals("OK", out.get("status").asText());
            assertEquals("you said: hello", out.get("answer").asText());
            assertEquals("node:1", out.get("citations").get(0).get("ref").asText());
            assertTrue(out.get("validated").asBoolean());
            assertTrue(out.get("applyVia").isNull(), "read-only skill carries no write endpoint");
        }
    }

    @Test
    void draftResultCarriesStructuredDataPayload(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/draft", TOKEN, "{\"userText\":\"weekdays 6am\"}");
            assertEquals(200, r.statusCode());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("OK", out.get("status").asText());
            assertTrue(out.get("validated").asBoolean(), "draft ran through the oracle");
            assertTrue(out.get("applyVia").isNull(), "draft-only: no write endpoint (V-9)");
            // The additive 'data' payload (since 3.4.0) round-trips through the route as JSON.
            JsonNode data = out.get("data");
            assertNotNull(data, "structured data payload present on the wire");
            assertEquals("0 6 * * MON-FRI", data.get("cron").asText());
            assertEquals("adjustment_etl", data.get("onPipeline").asText());
            assertEquals(2, data.get("nextRuns").size());
            assertTrue(data.get("draftToon").asText().contains("0 6 * * MON-FRI"),
                    "the saveable draft .toon is carried in the payload");
        }
    }

    @Test
    void unknownIntentIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/no-such-skill", TOKEN, "{}");
            assertEquals(404, r.statusCode());
            assertTrue(JSON.readTree(r.body()).get("error").asText().contains("unknown assist intent"));
        }
    }

    @Test
    void modelUnavailableIs503WithMessage(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> r = post(c.port, "/assist/down", TOKEN, "{}");
            assertEquals(503, r.statusCode());
            assertEquals("model offline", JSON.readTree(r.body()).get("error").asText());
        }
    }

    // ── v3.7.0: GET /assist/diagnoses (read-only failure diagnoses; scope assist.read) ──

    @Test
    void diagnosesRouteIsScopedAndReturnsAgentDiagnoses(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(401, get(c.port, "/assist/diagnoses", null).statusCode(), "no token -> locked");
            HttpResponse<String> r = get(c.port, "/assist/diagnoses", TOKEN);
            assertEquals(200, r.statusCode());
            JsonNode out = JSON.readTree(r.body());
            assertTrue(out.isArray() && out.size() == 1, "the agent's recent diagnoses come through as JSON");
            JsonNode d = out.get(0);
            assertEquals("B7", d.get("batchId").asText());
            assertEquals("CRITICAL", d.get("severity").asText());
            assertTrue(d.get("heuristicOnly").asBoolean());
            assertEquals("source:mini_etl", d.get("citations").get(0).get("ref").asText());
        }
    }

    @Test
    void diagnosesRouteReturnsEmptyWhenNoAgent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            HttpResponse<String> r = get(c.port, "/assist/diagnoses", TOKEN);
            assertEquals(200, r.statusCode(), "no agent -> empty list, not an error");
            assertTrue(JSON.readTree(r.body()).isArray());
            assertEquals(0, JSON.readTree(r.body()).size());
        }
    }
}
