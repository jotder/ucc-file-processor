package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.AgentSessionResult;
import com.gamma.intelligence.spi.IntelligenceAgent;
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

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, IntelligenceAgent agent) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
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
    void askWithoutAQuestionIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions/" + sessionId + "/ask", "{}");
            assertEquals(400, r.statusCode());
        }
    }

    /** A deterministic in-memory agent — no eoiagent/model dependency needed in the core test tree. */
    private static final class FakeIntelligenceAgent implements IntelligenceAgent {
        private final Map<String, String> sessions = new ConcurrentHashMap<>();

        @Override public String name() { return "fake-intelligence"; }
        @Override public void init(SourceService service) {}

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
            return new AgentAskResult("TEXT", "echo: " + request.question(), List.of(), null);
        }
    }
}
