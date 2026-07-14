package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the notification preference grid routes ({@code /notifications/preferences}). */
class ControlApiPreferencesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private JsonNode row(JsonNode grid, String category) {
        for (JsonNode r : grid) if (category.equals(r.get("category").asText())) return r;
        return null;
    }

    @Test
    void readEditAndPersistGrid(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode grid = json(send(c.port, "GET", "/notifications/preferences", null));
            assertTrue(grid.isArray() && grid.size() >= 3);
            JsonNode pipeline = row(grid, "pipeline");
            assertTrue(pipeline.get("channels").get("inApp").asBoolean());
            assertFalse(pipeline.get("channels").get("email").asBoolean());

            // edit pipeline: turn in-app off, email on
            String body = "{\"preferences\":[{\"category\":\"pipeline\","
                    + "\"channels\":{\"inApp\":false,\"email\":true}}]}";
            JsonNode updated = json(send(c.port, "PUT", "/notifications/preferences", body));
            JsonNode p2 = row(updated, "pipeline");
            assertFalse(p2.get("channels").get("inApp").asBoolean());
            assertTrue(p2.get("channels").get("email").asBoolean());

            // persisted on re-read
            JsonNode reread = row(json(send(c.port, "GET", "/notifications/preferences", null)), "pipeline");
            assertTrue(reread.get("channels").get("email").asBoolean());
        }
    }

    @Test
    void criticalCategoryStaysLocked(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode security = row(json(send(c.port, "GET", "/notifications/preferences", null)), "security");
            assertEquals(true, security.get("critical").asBoolean());
            assertTrue(security.get("channels").get("inApp").asBoolean());

            // attempt to disable → ignored (locked)
            String body = "{\"preferences\":[{\"category\":\"security\","
                    + "\"channels\":{\"inApp\":false,\"email\":false}}]}";
            JsonNode after = row(json(send(c.port, "PUT", "/notifications/preferences", body)), "security");
            assertTrue(after.get("channels").get("inApp").asBoolean(), "critical can't be silenced");
            assertTrue(after.get("channels").get("email").asBoolean());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
