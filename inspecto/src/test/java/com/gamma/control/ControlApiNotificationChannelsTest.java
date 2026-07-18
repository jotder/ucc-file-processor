package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;
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
 * {@code /notifications/channels*} admin CRUD over real HTTP (C4): the empty default, create with an
 * {@code enabled}/{@code createdAt} default, the 422 (missing fields) and 409 (duplicate id) gates, an
 * update that preserves {@code createdAt}, per-space isolation, delete, the 404s, and the 503 when the
 * write root is disabled (reads still degrade to an empty list). Channels persist as {@code channel}
 * components in the space's registry.
 */
class ControlApiNotificationChannelsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void channelCrudRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            String base = "/spaces/acme/notifications/channels";

            // empty before any save
            assertTrue(json(send(c.port, "GET", base, null)).isEmpty());

            // create — enabled defaults true, a createdAt stamp is issued
            HttpResponse<String> createResp = send(c.port, "POST", base,
                    "{\"id\":\"ops_mail\",\"kind\":\"EMAIL\",\"target\":\"ops@example.com\",\"description\":\"Ops inbox\"}");
            assertEquals(200, createResp.statusCode(), createResp.body());
            JsonNode made = json(createResp);
            assertEquals("EMAIL", made.get("kind").asText());
            assertTrue(made.get("enabled").asBoolean(), "enabled defaults true");
            long createdAt = made.get("createdAt").asLong();
            assertTrue(createdAt > 0, "a createdAt stamp is issued");

            // 422 missing target; 409 duplicate id
            assertEquals(422, send(c.port, "POST", base, "{\"id\":\"x\",\"kind\":\"EMAIL\"}").statusCode());
            assertEquals(409, send(c.port, "POST", base,
                    "{\"id\":\"ops_mail\",\"kind\":\"EMAIL\",\"target\":\"dup@example.com\"}").statusCode());

            assertEquals(1, json(send(c.port, "GET", base, null)).size());

            // update — target changed, disabled, createdAt preserved; id bound from the path
            JsonNode upd = json(send(c.port, "PUT", base + "/ops_mail",
                    "{\"id\":\"ignored\",\"kind\":\"EMAIL\",\"target\":\"ops2@example.com\",\"enabled\":false}"));
            assertEquals("ops2@example.com", upd.get("target").asText());
            assertFalse(upd.get("enabled").asBoolean());
            assertEquals("ops_mail", upd.get("id").asText(), "id is immutable — bound from the path");
            assertEquals(createdAt, upd.get("createdAt").asLong(), "createdAt preserved across update");

            // 404 update unknown
            assertEquals(404, send(c.port, "PUT", base + "/ghost",
                    "{\"id\":\"ghost\",\"kind\":\"EMAIL\",\"target\":\"g@example.com\"}").statusCode());

            // per-space isolation: beta has none
            assertTrue(json(send(c.port, "GET", "/spaces/beta/notifications/channels", null)).isEmpty());

            // delete + 404 on re-delete
            assertEquals("ops_mail", json(send(c.port, "DELETE", base + "/ops_mail", null)).get("deleted").asText());
            assertTrue(json(send(c.port, "GET", base, null)).isEmpty());
            assertEquals(404, send(c.port, "DELETE", base + "/ops_mail", null).statusCode());
        }
    }

    @Test
    void writesFailClosedWithoutWriteRootButListStaysOpen(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.clearProperty("assist.write.root");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            assertEquals(200, send(api.port(), "GET", "/notifications/channels", null).statusCode(),
                    "list degrades to empty when the write root is unset");
            assertTrue(json(send(api.port(), "GET", "/notifications/channels", null)).isEmpty());
            assertEquals(503, send(api.port(), "POST", "/notifications/channels",
                    "{\"id\":\"c\",\"kind\":\"EMAIL\",\"target\":\"c@example.com\"}").statusCode());
        } finally {
            api.close();
            svc.close();
            MetricRegistry.global().reset();
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
