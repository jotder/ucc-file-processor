package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Phase-1 Event Viewer routes over real HTTP: the live-tail feed
 * ({@code /events}), filtered search, event-by-id, CSV/JSON export, and saved-view CRUD. A pipeline
 * trigger generates a {@code BATCH_COMMITTED} domain event (plus captured INFO logs) to query against.
 */
class ControlApiEventsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port, String name) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    @Test
    void eventsFeedSearchDetailAndExport(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "POST", "/pipelines/" + c.name + "/trigger", null).statusCode());

            // live-tail feed: non-empty, carries a BATCH_COMMITTED domain event
            JsonNode recent = json(send(c.port, "GET", "/events?limit=200", null));
            assertTrue(recent.isArray() && recent.size() > 0, "events recorded");
            String commitId = null;
            for (JsonNode e : recent) {
                if ("BATCH_COMMITTED".equals(e.get("type").asText())) commitId = e.get("eventId").asText();
            }
            assertNotNull(commitId, "a BATCH_COMMITTED event is present");

            // filtered search by type
            JsonNode byType = json(send(c.port, "GET", "/events/search?type=BATCH_COMMITTED", null));
            assertTrue(byType.size() >= 1);
            assertEquals("BATCH_COMMITTED", byType.get(0).get("type").asText());
            assertEquals(c.name, byType.get(0).get("pipeline").asText());
            assertFalse(byType.get(0).get("correlationId").asText().isBlank(), "batchId threaded as correlationId");

            // event-by-id: hit + miss
            assertEquals(200, send(c.port, "GET", "/events/" + commitId, null).statusCode());
            assertEquals(404, send(c.port, "GET", "/events/no-such-id", null).statusCode());

            // CSV export
            HttpResponse<String> csv = send(c.port, "GET",
                    "/events/export?format=csv&type=BATCH_COMMITTED", null);
            assertEquals(200, csv.statusCode());
            assertTrue(csv.headers().firstValue("Content-Type").orElse("").startsWith("text/csv"));
            assertTrue(csv.body().startsWith("timestamp,level,type,source,pipeline,correlationId,message"));
            assertTrue(csv.body().contains("BATCH_COMMITTED"), "exported row present");
        }
    }

    @Test
    void savedViewsCrud(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode created = json(send(c.port, "POST", "/events/views",
                    "{\"name\":\"errors\",\"level\":\"ERROR\",\"q\":\"fail\"}"));
            assertEquals("errors", created.get("name").asText());
            assertEquals("ERROR", created.get("filters").get("level").asText());

            JsonNode list = json(send(c.port, "GET", "/events/views", null));
            assertTrue(list.isArray() && list.size() == 1);
            assertEquals("errors", list.get(0).get("name").asText());

            assertEquals(200, send(c.port, "POST", "/events/views/errors/delete", null).statusCode());
            assertEquals(0, json(send(c.port, "GET", "/events/views", null)).size());
            assertEquals(404, send(c.port, "POST", "/events/views/errors/delete", null).statusCode(),
                    "deleting a missing view → 404");

            assertEquals(400, send(c.port, "POST", "/events/views", "{}").statusCode(),
                    "name is required");
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
