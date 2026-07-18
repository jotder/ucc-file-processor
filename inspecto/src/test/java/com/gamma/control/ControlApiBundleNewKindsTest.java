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

/**
 * Metadata Bundle v2 (2026-07-18 follow-up): the three own-store kinds {@code authored-pipeline}/
 * {@code job}/{@code saved-view} over real HTTP — export, preview and import round-trip through the
 * {@code BundleSource} seam, exactly like the pre-existing {@link ComponentStore} kinds. {@code connection}
 * stays 422 (unsupported) — a deliberate, documented exclusion (secret-policy gated), covered already by
 * {@link ControlApiBundleTest#exportRejectsUnsupportedKindAndEmptySelection}.
 */
class ControlApiBundleNewKindsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    private static String bundleOf(String kind, String id, String contentJson) {
        return "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"exportedAt\":\"2026-07-18T00:00:00Z\","
                + "\"sourceSpace\":null,\"items\":[{\"kind\":\"" + kind + "\",\"id\":\"" + id + "\","
                + "\"content\":" + contentJson + "}]}";
    }

    // ── authored-pipeline ────────────────────────────────────────────────────────

    @Test
    void authoredPipelineImportsAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"p1\",\"active\":false,\"nodes\":[],\"edges\":[]}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("authored-pipeline", "p1", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());
            assertEquals(200, send(c.port, "GET", "/pipelines/authored/p1", null).statusCode(),
                    "landed in PipelineStore, readable via the real CRUD route");

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"authored-pipeline\",\"id\":\"p1\"}]}"));
            assertEquals(1, exp.get("bundle").get("items").size());
            assertEquals("p1", exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            // re-import identical content → idempotent
            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("authored-pipeline", "p1", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    // ── job ──────────────────────────────────────────────────────────────────────

    @Test
    void jobImportHotRegistersAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"nightly_cleanup\",\"type\":\"maintenance\",\"task\":\"cleanup\",\"cron\":\"0 3 * * *\"}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("job", "nightly_cleanup", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());

            JsonNode detail = json(send(c.port, "GET", "/jobs/nightly_cleanup", null));
            assertEquals("maintenance", detail.get("type").asText(), "hot-registered on the live JobService");
            assertEquals("0 3 * * *", detail.get("cron").asText());

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"job\",\"id\":\"nightly_cleanup\"}]}"));
            assertEquals("nightly_cleanup", exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("job", "nightly_cleanup", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    // ── saved-view ───────────────────────────────────────────────────────────────

    @Test
    void savedViewImportsAndExportsRoundTrip(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"errors_only\",\"filters\":{\"level\":\"ERROR\"},\"createdAt\":1000}";
            JsonNode imp = json(send(c.port, "POST", "/bundle/import", bundleOf("saved-view", "errors_only", content)));
            assertEquals(1, imp.get("imported").asInt(), imp.toString());

            JsonNode list = json(send(c.port, "GET", "/events/views", null));
            assertEquals(1, list.size());
            assertEquals("ERROR", list.get(0).get("filters").get("level").asText());

            JsonNode exp = json(send(c.port, "POST", "/bundle/export",
                    "{\"items\":[{\"kind\":\"saved-view\",\"id\":\"errors_only\"}]}"));
            assertEquals("errors_only", exp.get("bundle").get("items").get(0).get("content").get("name").asText());

            JsonNode imp2 = json(send(c.port, "POST", "/bundle/import", bundleOf("saved-view", "errors_only", content)));
            assertEquals(1, imp2.get("unchanged").asInt(), imp2.toString());
        }
    }

    // ── preview + requires across the new kinds ─────────────────────────────────

    @Test
    void previewClassifiesNewKindsAndConnectionStaysUnsupported(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String content = "{\"name\":\"p2\",\"active\":false,\"nodes\":[],\"edges\":[]}";
            send(c.port, "POST", "/bundle/import", bundleOf("authored-pipeline", "p2", content));

            JsonNode preview = json(send(c.port, "POST", "/bundle/preview",
                    "{\"format\":\"inspecto-metadata-bundle\",\"version\":2,\"items\":["
                    + "{\"kind\":\"authored-pipeline\",\"id\":\"p2\",\"content\":" + content + "},"
                    + "{\"kind\":\"authored-pipeline\",\"id\":\"brand_new\",\"content\":" + content + "},"
                    + "{\"kind\":\"connection\",\"id\":\"pg\",\"content\":{}}]}"));
            assertEquals("unchanged", preview.get("items").get(0).get("status").asText());
            assertEquals("new", preview.get("items").get(1).get("status").asText());
            assertEquals("unsupported", preview.get("items").get(2).get("status").asText());
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
