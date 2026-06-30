package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
 * Integration tests for {@code POST /runs} (v4.1.0, scope {@code control}): register a new
 * pipeline at runtime from a {@code .toon} on disk under {@code -Dassist.write.root}, so the running
 * service processes it on the next poll cycle without a restart (pairs with {@code POST /config/write}).
 * Covers the fail-closed gate (registration disabled → bad body → path jail → missing file → invalid
 * config → id collision) and that a registered pipeline appears in the live {@code GET /runs}.
 */
class ControlApiPipelineCreateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            System.clearProperty("assist.write.root");
            System.clearProperty("assist.safety.roots");
        }
    }

    /** Boot with one startup pipeline (MINI_ETL in {@code cfg}). {@code root==null} ⇒ writes disabled. */
    private Ctx open(Path cfg, Path root) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        if (root != null) {
            System.setProperty("assist.write.root", root.toString());
            // The config's own dirs live under the same base, so widen the safety jail to it; the
            // property is read per-request in defaultPolicy(), so it stays set until Ctx.close().
            System.setProperty("assist.safety.roots", root.toString());
        } else {
            System.clearProperty("assist.write.root");
            System.clearProperty("assist.safety.roots");
        }
        SourceService svc = new SourceService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();   // HTTP only; we never start svc polling, so registration adds without ingesting
        return new Ctx(svc, api, api.port());
    }

    /** Author a known-loadable pipeline (the test fixture's shape, renamed) under {@code writeRoot}. */
    private Path authorPipeline(Path writeRoot, String fileName, String pipelineName) throws Exception {
        Files.createDirectories(writeRoot);
        // writePipeline emits a valid MINI_ETL + its schema with all dirs under writeRoot.
        Path fixture = PipelineConfigBatchTest.writePipeline(writeRoot, "");
        String toon = Files.readString(fixture).replace("name: MINI_ETL", "name: " + pipelineName);
        Path out = writeRoot.resolve(fileName);
        Files.writeString(out, toon);
        return out;
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("GET", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    private static String body(String configPath) {
        return "{\"configPath\":\"" + configPath + "\"}";
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, post(c.port, "/runs", body("x.toon")).statusCode(),
                    "no -Dassist.write.root ⇒ registration disabled");
        }
    }

    @Test
    void registersNewPipelineAndItBecomesLive(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(1, c.svc.pipelines().size(), "only the startup pipeline at first");
            authorPipeline(root, "orders.toon", "ORDERS");

            HttpResponse<String> r = post(c.port, "/runs", body("orders.toon"));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertTrue(out.get("registered").asBoolean());
            assertEquals("orders", out.get("id").asText(), "in-file name is lowercased to the id");
            assertEquals("orders.toon", out.get("path").asText());
            assertEquals("orders", out.get("pipeline").get("name").asText());

            // Live: the service now lists it and GET /runs reflects it — no restart.
            assertEquals(2, c.svc.pipelines().size());
            JsonNode list = JSON.readTree(get(c.port, "/runs").body());
            boolean found = false;
            for (JsonNode p : list) if ("orders".equals(p.get("name").asText())) found = true;
            assertTrue(found, "registered pipeline appears in GET /runs: " + list);
        }
    }

    @Test
    void registrationIsIdempotentOnTheSamePath(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            authorPipeline(root, "orders.toon", "ORDERS");
            assertEquals(200, post(c.port, "/runs", body("orders.toon")).statusCode());
            assertEquals(200, post(c.port, "/runs", body("orders.toon")).statusCode(),
                    "re-registering the same file is a no-op success");
            assertEquals(2, c.svc.pipelines().size(), "not double-counted");
        }
    }

    @Test
    void collidingIdFromADifferentFileIs409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // A different file whose in-file name collides with the startup MINI_ETL.
            authorPipeline(root, "shadow.toon", "MINI_ETL");
            assertEquals(409, post(c.port, "/runs", body("shadow.toon")).statusCode(),
                    "must not silently shadow a registered pipeline");
        }
    }

    @Test
    void missingPathIs400AndEscapingPathIs403AndAbsentFileIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(400, post(c.port, "/runs", "{}").statusCode(), "no configPath");
            assertEquals(403, post(c.port, "/runs", body("../escape.toon")).statusCode(),
                    "path escaping the write root is blocked");
            assertEquals(404, post(c.port, "/runs", body("ghost.toon")).statusCode(),
                    "no file at the resolved path");
        }
    }

    @Test
    void invalidConfigIs422AndNotRegistered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String r = root.toString().replace('\\', '/');
            // ingester set but no segments → the plugin-ingester-requires-segments spec ERROR.
            String bad = """
                    name: BROKEN
                    dirs:
                      poll: %s/inbox
                      database: %s/db
                    processing:
                      ingester: com.x.Plugin
                      threads: 1
                    """.formatted(r, r);
            Files.writeString(root.resolve("broken.toon"), bad);
            assertEquals(422, post(c.port, "/runs", body("broken.toon")).statusCode());
            assertEquals(1, c.svc.pipelines().size(), "nothing registered on an invalid config");
        }
    }

    @Test
    void unresolvableSchemaFileIs422WithFieldAnchoredFinding(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // A structurally valid pipeline whose schema_file points at nothing on this host:
            // pre-flight must block with a finding anchored to the field, not an opaque load error.
            Path fixture = PipelineConfigBatchTest.writePipeline(root, "");
            String ghost = root.resolve("ghost_schema.toon").toString().replace('\\', '/');
            String toon = Files.readString(fixture)
                    .replace("name: MINI_ETL", "name: GHOSTED")
                    .replaceAll("schema_file: \"[^\"]*\"", "schema_file: \"" + ghost + "\"");
            Files.writeString(root.resolve("ghosted.toon"), toon);

            HttpResponse<String> r = post(c.port, "/runs", body("ghosted.toon"));
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertFalse(out.get("registered").asBoolean());
            boolean anchored = false;
            for (JsonNode f : out.get("findings"))
                if ("processing.schema_file".equals(f.get("fieldPath").asText())
                        && f.get("message").asText().contains("ghost_schema.toon")) anchored = true;
            assertTrue(anchored, "expected a processing.schema_file finding: " + r.body());
            assertEquals(1, c.svc.pipelines().size(), "nothing registered");
        }
    }

}
