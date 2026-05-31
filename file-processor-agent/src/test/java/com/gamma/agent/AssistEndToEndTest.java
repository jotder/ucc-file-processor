package com.gamma.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.agent.model.FakeModelProvider;
import com.gamma.agent.model.ModelRouter;
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
