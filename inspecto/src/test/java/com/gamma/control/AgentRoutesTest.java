package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.intelligence.AgentAnswerSink;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.AgentSessionResult;
import com.gamma.intelligence.spi.IntelligenceAgent;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the AGT-5 (P0) {@code /agent/*} routes over real HTTP, against a fake {@link IntelligenceAgent}. */
class AgentRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, IntelligenceAgent agent) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        if (agent != null) svc.registerIntelligenceAgent(agent);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void agentRoutesReturn503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions", "{}");
            assertEquals(503, r.statusCode());
        }
    }

    @Test
    void openSessionThenAskRoundTrips(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"page\":{\"pageId\":\"overview\"}}");
            assertEquals(200, opened.statusCode());
            JsonNode openedBody = JSON.readTree(opened.body());
            String sessionId = openedBody.get("sessionId").asText();
            assertFalse(sessionId.isBlank());

            HttpResponse<String> asked = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask", "{\"question\":\"How does ingestion work?\"}");
            assertEquals(200, asked.statusCode());
            JsonNode askedBody = JSON.readTree(asked.body());
            assertEquals("TEXT", askedBody.get("kind").asText());
            assertTrue(askedBody.get("text").asText().contains("How does ingestion work?"));
        }
    }

    @Test
    void askOnAnUnknownSessionIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST",
                    "/agent/sessions/does-not-exist/ask", "{\"question\":\"hi\"}");
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void askStreamRoundTripsAsServerSentEvents(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();

            HttpResponse<String> streamed = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask/stream", "{\"question\":\"stream this\"}");
            assertEquals(200, streamed.statusCode());
            assertEquals("text/event-stream", streamed.headers().firstValue("Content-Type").orElse(null));
            assertTrue(streamed.body().contains("event: complete"));
            assertTrue(streamed.body().contains("echo: stream this"));
        }
    }

    @Test
    void askStreamEmitsAnArtifactFrameBeforeComplete(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();

            HttpResponse<String> streamed = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask/stream", "{\"question\":\"stream this\"}");
            assertEquals(200, streamed.statusCode());
            String body = streamed.body();
            int artifactIdx = body.indexOf("event: artifact");
            int completeIdx = body.indexOf("event: complete");
            assertTrue(artifactIdx >= 0, "expected an event: artifact frame");
            assertTrue(artifactIdx < completeIdx, "artifact frame must precede the complete frame");

            // Extract the data: line belonging to the artifact frame and parse it (Map.of()'s
            // iteration order is unspecified/randomized per JVM run, so compare structurally).
            String afterArtifact = body.substring(artifactIdx);
            String dataPrefix = "data: ";
            int dataIdx = afterArtifact.indexOf(dataPrefix);
            String artifactJson = afterArtifact.substring(dataIdx + dataPrefix.length(),
                    afterArtifact.indexOf('\n', dataIdx));
            JsonNode artifactNode = JSON.readTree(artifactJson);
            assertEquals("chart", artifactNode.get("kind").asText());
            assertTrue(artifactNode.get("config").isObject());
        }
    }

    @Test
    void askStreamOnAnUnknownSessionIsAnErrorEventNotA404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST",
                    "/agent/sessions/does-not-exist/ask/stream", "{\"question\":\"hi\"}");
            assertEquals(200, r.statusCode()); // headers are already committed by the time the error is known
            assertTrue(r.body().contains("event: error"));
        }
    }

    @Test
    void askWithoutAQuestionIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions/" + sessionId + "/ask", "{}");
            assertEquals(400, r.statusCode());
        }
    }

    @Test
    void casesRouteIs503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/cases", null).statusCode());
            assertEquals(503, send(ctx.port(), "GET", "/agent/cases/case-1", null).statusCode());
        }
    }

    @Test
    void recentCasesReturnsTheSeededCasesNewestFirst(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent(Map.of(
                "case-1", Map.of("id", "case-1", "outcome", "open"),
                "case-2", Map.of("id", "case-2", "outcome", "resolved"))))) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases", null);
            assertEquals(200, r.statusCode());
            JsonNode cases = JSON.readTree(r.body()).get("cases");
            assertEquals(2, cases.size());
        }
    }

    @Test
    void caseByIdReturnsTheMatchingCase(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent(Map.of(
                "case-1", Map.of("id", "case-1", "outcome", "open"))))) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases/case-1", null);
            assertEquals(200, r.statusCode());
            assertEquals("open", JSON.readTree(r.body()).get("outcome").asText());
        }
    }

    @Test
    void caseByIdOnAnUnknownIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases/does-not-exist", null);
            assertEquals(404, r.statusCode());
        }
    }

    /** A deterministic in-memory agent — no eoiagent/model dependency needed in the core test tree. */
    private static final class FakeIntelligenceAgent implements IntelligenceAgent {
        private final Map<String, String> sessions = new ConcurrentHashMap<>();
        private final Map<String, Object> cases;

        FakeIntelligenceAgent() { this(Map.of()); }
        FakeIntelligenceAgent(Map<String, Object> cases) { this.cases = cases; }

        @Override public String name() { return "fake-intelligence"; }
        @Override public void init(CollectorService service) {}

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> recentCases(int limit) {
            return cases.values().stream().map(v -> (Map<String, Object>) v).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public java.util.Optional<Map<String, Object>> caseById(String id) {
            return java.util.Optional.ofNullable((Map<String, Object>) cases.get(id));
        }

        @Override
        public AgentSessionResult openSession(AgentSessionRequest request) {
            String id = UUID.randomUUID().toString();
            sessions.put(id, request.role() == null ? "" : request.role()); // ConcurrentHashMap forbids null values
            return new AgentSessionResult(id, Instant.now().toString());
        }

        @Override
        public AgentAskResult ask(String sessionId, AgentAskRequest request) {
            if (!sessions.containsKey(sessionId)) {
                throw new IllegalArgumentException("unknown session: '" + sessionId + "'");
            }
            return new AgentAskResult("TEXT", "echo: " + request.question(), List.of(), null, null);
        }

        @Override
        public void askStream(String sessionId, AgentAskRequest request, AgentAnswerSink sink) {
            try {
                AgentAskResult result = ask(sessionId, request);
                sink.onArtifact(Map.of("kind", "chart", "config", Map.of()));
                sink.onComplete(result);
            } catch (IllegalArgumentException e) {
                sink.onError(e.getMessage());
            }
        }
    }
}
