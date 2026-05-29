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
 * Integration tests for the M3 {@link ControlApi} over real HTTP: health/readiness,
 * token auth, pipeline listing, single-pipeline trigger, audit queries
 * (commits/batches/files), pause/resume, config validation, reprocess, and 404s.
 */
class ControlApiTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** Wires a SourceService over one seeded pipeline + a started ControlApi. */
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
        ControlApi api = new ControlApi(svc, 0, TOKEN);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    private HttpResponse<String> send(int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    @Test
    void healthAndReadyAreOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> health = send(c.port, "GET", "/health", null, null);
            assertEquals(200, health.statusCode());
            assertEquals("UP", json(health).get("status").asText());

            HttpResponse<String> ready = send(c.port, "GET", "/ready", null, null);
            assertEquals(200, ready.statusCode());
            assertEquals(1, json(ready).get("pipelines").asInt());
        }
    }

    @Test
    void authIsRequiredAndEnforced(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(401, send(c.port, "GET", "/pipelines", null, null).statusCode(), "no token");
            assertEquals(401, send(c.port, "GET", "/pipelines", "wrong", null).statusCode(), "bad token");

            HttpResponse<String> ok = send(c.port, "GET", "/pipelines", TOKEN, null);
            assertEquals(200, ok.statusCode());
            JsonNode arr = json(ok);
            assertTrue(arr.isArray() && arr.size() == 1);
            assertEquals(c.name, arr.get(0).get("name").asText());
            assertFalse(arr.get(0).get("paused").asBoolean());
        }
    }

    @Test
    void triggerRunsPipelineThenAuditQueriesReturnData(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> run = send(c.port, "POST", "/pipelines/" + c.name + "/trigger", TOKEN, null);
            assertEquals(200, run.statusCode());
            assertEquals(1, json(run).get("total").asInt());
            assertEquals(0, json(run).get("failed").asInt());

            assertFalse(json(send(c.port, "GET", "/pipelines/" + c.name + "/commits", TOKEN, null)).isEmpty(),
                    "a committed batch should be visible");
            assertTrue(json(send(c.port, "GET", "/pipelines/" + c.name + "/batches", TOKEN, null)).size() >= 1,
                    "batch audit rows present");
            assertTrue(json(send(c.port, "GET", "/pipelines/" + c.name + "/files", TOKEN, null)).size() >= 1,
                    "file audit rows present");
            // lineage carries partition rows for the committed batch
            assertTrue(json(send(c.port, "GET", "/pipelines/" + c.name + "/lineage", TOKEN, null)).size() >= 1);
        }
    }

    @Test
    void pauseAndResumeToggleState(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "POST", "/pipelines/" + c.name + "/pause", TOKEN, null).statusCode());
            JsonNode listed = json(send(c.port, "GET", "/pipelines", TOKEN, null));
            assertTrue(listed.get(0).get("paused").asBoolean(), "pipeline reports paused");

            assertEquals(200, send(c.port, "POST", "/pipelines/" + c.name + "/resume", TOKEN, null).statusCode());
            JsonNode after = json(send(c.port, "GET", "/pipelines", TOKEN, null));
            assertFalse(after.get(0).get("paused").asBoolean(), "pipeline reports resumed");
        }
    }

    @Test
    void validateReturnsConfigWarnings(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path toon = c.svc.pathFor(c.name).orElseThrow();
            String body = "{\"configPath\":\"" + toon.toString().replace("\\", "/") + "\"}";
            HttpResponse<String> r = send(c.port, "POST", "/validate", TOKEN, body);
            assertEquals(200, r.statusCode());
            assertEquals(c.name, json(r).get("pipeline").asText());
            assertTrue(json(r).has("warnings"));
        }
    }

    @Test
    void unknownPipelineAndPathYield404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c.port, "GET", "/pipelines/nope/commits", TOKEN, null).statusCode());
            assertEquals(404, send(c.port, "GET", "/no/such/route", TOKEN, null).statusCode());
            assertEquals(405, send(c.port, "GET", "/trigger", TOKEN, null).statusCode(), "GET on POST-only route");
        }
    }

    @Test
    void reprocessReplaysACommittedBatch(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            send(c.port, "POST", "/pipelines/" + c.name + "/trigger", TOKEN, null);
            JsonNode commits = json(send(c.port, "GET", "/pipelines/" + c.name + "/commits", TOKEN, null));
            assertFalse(commits.isEmpty());
            String batchId = commits.get(0).asText();

            String body = "{\"batchId\":\"" + batchId + "\"}";
            HttpResponse<String> r = send(c.port, "POST", "/pipelines/" + c.name + "/reprocess", TOKEN, body);
            assertEquals(200, r.statusCode(), "reprocess body: " + r.body());
            assertEquals("reprocessed", json(r).get("status").asText());
            assertEquals(batchId, json(r).get("batchId").asText());
        }
    }
}
