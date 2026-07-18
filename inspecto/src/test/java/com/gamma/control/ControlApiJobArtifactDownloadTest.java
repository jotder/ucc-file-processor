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
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code GET /jobs/{name}/runs/{runId}/artifacts/{artifact}/content} over real HTTP: a report Job delivers
 * its snapshot to a file, records it as a {@code file} Run Artifact, and the route streams the bytes back as
 * an attachment. Covers the happy path plus the 404 gates (unknown artifact name, unknown run).
 */
class ControlApiJobArtifactDownloadTest {

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
    void downloadsAReportRunsFileArtifact(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String base = "/spaces/acme";
            String outDir = root.resolve("reports").toString().replace("\\", "/");

            // a report Job that delivers its status snapshot to out_dir as a JSON file
            assertEquals(200, send(c.port, "POST", base + "/jobs",
                    "{\"name\":\"daily_report\",\"type\":\"report\",\"scope\":\"status\","
                    + "\"out_dir\":\"" + outDir + "\",\"cron\":\"0 6 * * *\"}").statusCode());

            // fire it and wait for the run to finish (legacy trigger returns no runId — read it from history)
            send(c.port, "POST", base + "/jobs/daily_report/trigger", null);
            String runId = pollForSuccessfulRun(c.port, base, "daily_report");

            // the run recorded a 'report' file artifact
            JsonNode artifacts = json(send(c.port, "GET", base + "/jobs/daily_report/runs/" + runId + "/artifacts", null));
            assertTrue(artifacts.isArray() && !artifacts.isEmpty(), "the report run recorded an artifact: " + artifacts);
            assertEquals("report", artifacts.get(0).get("name").asText());
            assertEquals("file", artifacts.get(0).get("kind").asText());

            // download its bytes — attachment, JSON content-type, non-empty body
            HttpResponse<String> dl = send(c.port, "GET",
                    base + "/jobs/daily_report/runs/" + runId + "/artifacts/report/content", null);
            assertEquals(200, dl.statusCode(), dl.body());
            assertTrue(dl.headers().firstValue("Content-Disposition").orElse("").contains("attachment"),
                    "served as an attachment download");
            assertTrue(dl.headers().firstValue("Content-Type").orElse("").contains("application/json"),
                    "content-type inferred from the .json artifact filename");
            assertFalse(dl.body().isBlank(), "the delivered report file has content");

            // 404 gates
            assertEquals(404, send(c.port, "GET",
                    base + "/jobs/daily_report/runs/" + runId + "/artifacts/nope/content", null).statusCode(),
                    "unknown artifact name");
            assertEquals(404, send(c.port, "GET",
                    base + "/jobs/daily_report/runs/no_such_run/artifacts/report/content", null).statusCode(),
                    "unknown run id");
        }
    }

    /** Trigger fires off the request thread; poll the run history until the (single) run reaches SUCCESS. */
    private String pollForSuccessfulRun(int port, String base, String job) throws Exception {
        for (int i = 0; i < 150; i++) {
            JsonNode runs = json(send(port, "GET", base + "/jobs/" + job + "/runs", null));
            for (JsonNode run : runs) {
                String status = run.get("status").asText();
                if ("SUCCESS".equals(status)) return run.get("runId").asText();
                if ("FAILED".equals(status) || "ERROR".equals(status)) fail("report run failed: " + run);
            }
            Thread.sleep(100);
        }
        fail("report run for '" + job + "' did not complete within timeout");
        return null;   // unreachable
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
