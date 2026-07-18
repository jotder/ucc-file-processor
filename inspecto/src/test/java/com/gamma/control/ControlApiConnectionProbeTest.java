package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The connection-workbench routes over real HTTP ({@code POST /connections/{id}/probe},
 * {@code GET /connections/{id}/explore|sample}) — every gate per the endpoint pattern: 404 unknown id,
 * 422 unknown check, 400 missing sample path, 403 path escape (jail), 501 connector without workbench
 * support, plus the local-profile happy paths.
 */
class ControlApiConnectionProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, ConnectionProfile... profiles) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        for (ConnectionProfile p : profiles) svc.registerConnection(p);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private static ConnectionProfile localProfile(Path root) {
        return ConnectionProfile.fromMap(Map.of("id", "files", "connector", "local", "base_path", root.toString()));
    }

    @Test
    void unknownConnectionIdIs404OnAllThreeRoutes(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c.port, "POST", "/connections/none/probe", "{}").statusCode());
            assertEquals(404, send(c.port, "GET", "/connections/none/explore", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/connections/none/sample?path=x", null).statusCode());
        }
    }

    @Test
    void probeOfALocalProfileRunsTheGradedChecks(@TempDir Path dir, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("feed.csv"), "id\n1\n");
        try (Ctx c = open(dir, localProfile(root))) {
            JsonNode r = json(send(c.port, "POST", "/connections/files/probe", "{}"));
            assertTrue(r.get("ok").asBoolean(), r.toString());
            assertTrue(r.get("secretsResolved").asBoolean());
            assertEquals(5, r.get("checks").size());

            // a bounded subset is honoured
            JsonNode sub = json(send(c.port, "POST", "/connections/files/probe",
                    "{\"checks\":[\"reachability\",\"read\"]}"));
            assertEquals(2, sub.get("checks").size());

            // unknown check name → 422
            assertEquals(422, send(c.port, "POST", "/connections/files/probe",
                    "{\"checks\":[\"fly\"]}").statusCode());
        }
    }

    @Test
    void exploreAndSampleServeTheLocalTreeAndEnforceTheJail(@TempDir Path dir, @TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("inbox"));
        Files.writeString(root.resolve("inbox").resolve("feed.csv"), "id,name\n1,a\n2,b\n");
        try (Ctx c = open(dir, localProfile(root))) {
            JsonNode rootNodes = json(send(c.port, "GET", "/connections/files/explore", null));
            assertEquals(1, rootNodes.size());
            assertEquals("inbox", rootNodes.get(0).get("name").asText());
            assertEquals("dir", rootNodes.get(0).get("kind").asText());

            JsonNode inbox = json(send(c.port, "GET", "/connections/files/explore?path=inbox", null));
            assertEquals("inbox/feed.csv", inbox.get(0).get("path").asText());
            assertEquals("file", inbox.get(0).get("kind").asText());

            JsonNode sample = json(send(c.port, "GET", "/connections/files/sample?path="
                    + enc("inbox/feed.csv") + "&limit=1", null));
            assertEquals("id", sample.get("columns").get(0).asText());
            assertEquals(1, sample.get("rows").size());
            assertTrue(sample.get("truncated").asBoolean());

            // gates: jail escape → 403; missing path param → 400; unknown path → 404
            assertEquals(403, send(c.port, "GET", "/connections/files/explore?path="
                    + enc("../../"), null).statusCode(), "path jail");
            assertEquals(400, send(c.port, "GET", "/connections/files/sample", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/connections/files/sample?path=ghost.csv", null).statusCode());
        }
    }

    @Test
    void connectorWithoutWorkbenchSupportProbesSkippedAndRefusesBrowse(@TempDir Path dir) throws Exception {
        try (java.net.ServerSocket listening = new java.net.ServerSocket(0)) {
            ConnectionProfile sftp = ConnectionProfile.fromMap(Map.of(
                    "id", "remote", "connector", "sftp", "host", "127.0.0.1",
                    "port", String.valueOf(listening.getLocalPort()),
                    "options", Map.of("test_timeout_ms", "500")));
            try (Ctx c = open(dir, sftp)) {
                JsonNode r = json(send(c.port, "POST", "/connections/remote/probe", "{}"));
                assertTrue(r.get("checks").get(0).get("ok").asBoolean(), "reachability is real");
                for (int i = 1; i < r.get("checks").size(); i++)
                    assertTrue(r.get("checks").get(i).get("skipped").asBoolean(),
                            "graded checks are skipped, never fabricated: " + r.get("checks").get(i));

                assertEquals(501, send(c.port, "GET", "/connections/remote/explore", null).statusCode());
                assertEquals(501, send(c.port, "GET", "/connections/remote/sample?path=x", null).statusCode());
            }
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
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
