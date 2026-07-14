package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Scheduler's per-job actions over real HTTP (the UI's live path): single-job detail
 * ({@code GET /jobs/{name}}), the enable/disable toggle and reschedule (all three persist the job's
 * TOON under the space's write root and hot-apply on the live JobService), and the UI-shaped run-log
 * view ({@code GET .../runs/{id}/logs} → {@code {logs:[{ts,level,message}], events:[]}}).
 */
class ControlApiJobActionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void detailToggleAndRescheduleRoundTrip(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";

            // create a cron job through the existing CRUD, then read it back as a single-job detail
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"nightly_cleanup\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}")
                    .statusCode());
            JsonNode detail = json(send(c.port, "GET", base + "/jobs/nightly_cleanup", null));
            assertEquals("nightly_cleanup", detail.get("name").asText());
            assertEquals("0 3 * * *", detail.get("cron").asText());
            assertTrue(detail.get("enabled").asBoolean());

            // unknown job -> 404 (and the fixed /jobs sub-paths still resolve to their own routes)
            assertEquals(404, send(c.port, "GET", base + "/jobs/nope", null).statusCode());
            assertEquals(200, send(c.port, "GET", base + "/jobs/types", null).statusCode());

            // disable -> persisted enabled:false + reflected in the detail; enable flips it back
            JsonNode disabled = json(send(c.port, "POST", base + "/jobs/nightly_cleanup/disable", "{}"));
            assertFalse(disabled.get("enabled").asBoolean());
            Path toon = root.resolve("acme").resolve("config").resolve("jobs").resolve("nightly_cleanup_job.toon");
            assertTrue(Files.readString(toon).contains("enabled: false"), "persisted to the job TOON");
            assertTrue(json(send(c.port, "POST", base + "/jobs/nightly_cleanup/enable", "{}"))
                    .get("enabled").asBoolean());

            // reschedule replaces the cron (422 without one)
            JsonNode moved = json(send(c.port, "POST", base + "/jobs/nightly_cleanup/reschedule",
                    "{\"cron\":\"30 4 * * *\"}"));
            assertEquals("30 4 * * *", moved.get("cron").asText());
            assertTrue(Files.readString(toon).contains("30 4 * * *"));
            assertEquals(422, send(c.port, "POST", base + "/jobs/nightly_cleanup/reschedule", "{}").statusCode());

            // the UI-shaped run-log view: trigger one run, then read its /logs
            JsonNode fired = json(send(c.port, "POST", base + "/jobs/nightly_cleanup/trigger", null));
            String runId = fired.has("runId") ? fired.get("runId").asText() : null;
            if (runId != null) {
                Thread.sleep(300);   // the run executes off the request thread
                JsonNode logs = json(send(c.port, "GET", base + "/jobs/nightly_cleanup/runs/" + runId + "/logs", null));
                assertTrue(logs.has("logs") && logs.get("logs").isArray(), logs.toString());
                assertTrue(logs.has("events") && logs.get("events").isArray());
                if (!logs.get("logs").isEmpty()) {
                    JsonNode line = logs.get("logs").get(0);
                    assertTrue(line.has("ts") && line.has("level") && line.has("message"));
                }
            }
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
