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
 * {@code /notifications/rules*} admin CRUD over real HTTP: the empty default, create with defaulted
 * templates/{@code enabled}, the 422 (missing fields, bad {@code minLevel}) and 409 (duplicate id) gates,
 * an update whose id is bound from the path, per-space isolation, delete, the 404s, and the 503 when the
 * write root is disabled (reads still degrade to an empty list). Rules persist as {@code notification-rule}
 * components in the space's registry (mirrors {@link ControlApiNotificationChannelsTest}).
 */
class ControlApiNotificationRulesTest {

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
    void ruleCrudRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());
            String base = "/spaces/acme/notifications/rules";

            // empty before any save
            assertTrue(json(send(c.port, "GET", base, null)).isEmpty());

            // create — enabled defaults true, templates default
            HttpResponse<String> createResp = send(c.port, "POST", base,
                    "{\"id\":\"custom1\",\"eventType\":\"job.custom\",\"category\":\"job\"}");
            assertEquals(200, createResp.statusCode(), createResp.body());
            JsonNode made = json(createResp);
            assertEquals("job.custom", made.get("eventType").asText());
            assertTrue(made.get("enabled").asBoolean(), "enabled defaults true");
            assertEquals("{{type}}", made.get("titleTemplate").asText());

            // 422 missing category; 409 duplicate id
            assertEquals(422, send(c.port, "POST", base, "{\"id\":\"x\",\"eventType\":\"job.custom\"}").statusCode());
            assertEquals(409, send(c.port, "POST", base,
                    "{\"id\":\"custom1\",\"eventType\":\"job.custom\",\"category\":\"job\"}").statusCode());

            // 422 unknown minLevel
            assertEquals(422, send(c.port, "POST", base,
                    "{\"id\":\"bad\",\"eventType\":\"job.custom\",\"category\":\"job\",\"minLevel\":\"bogus\"}")
                    .statusCode());

            assertEquals(1, json(send(c.port, "GET", base, null)).size());

            // update — disabled, id bound from the path
            JsonNode upd = json(send(c.port, "PUT", base + "/custom1",
                    "{\"id\":\"ignored\",\"eventType\":\"job.custom\",\"category\":\"job\",\"enabled\":false}"));
            assertFalse(upd.get("enabled").asBoolean());
            assertEquals("custom1", upd.get("id").asText(), "id is immutable — bound from the path");

            // 404 update unknown
            assertEquals(404, send(c.port, "PUT", base + "/ghost",
                    "{\"id\":\"ghost\",\"eventType\":\"job.custom\",\"category\":\"job\"}").statusCode());

            // per-space isolation: beta has none
            assertTrue(json(send(c.port, "GET", "/spaces/beta/notifications/rules", null)).isEmpty());

            // delete + 404 on re-delete
            assertEquals("custom1", json(send(c.port, "DELETE", base + "/custom1", null)).get("deleted").asText());
            assertTrue(json(send(c.port, "GET", base, null)).isEmpty());
            assertEquals(404, send(c.port, "DELETE", base + "/custom1", null).statusCode());
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
            assertEquals(200, send(api.port(), "GET", "/notifications/rules", null).statusCode(),
                    "list degrades to empty when the write root is unset");
            assertTrue(json(send(api.port(), "GET", "/notifications/rules", null)).isEmpty());
            assertEquals(503, send(api.port(), "POST", "/notifications/rules",
                    "{\"id\":\"c\",\"eventType\":\"job.custom\",\"category\":\"job\"}").statusCode());
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
