package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
 * Integration tests for the stream-onboarding draft lifecycle (v5.1.0) over real HTTP:
 * {@code POST /config/write} (create) → {@code POST /runs} (register) → the draft shows in
 * {@code GET /catalog/streams} with {@code active:false} → {@code GET /config/{type}/{name}}
 * (resume read-back) → overwrite (stage save) → {@code DELETE} (discard). Plus the read route's
 * fail-closed gates and the stateless {@code POST /config/preview/parsing} sample preview.
 */
class ControlApiOnboardingLifecycleTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .DELETE().build(), BodyHandlers.ofString());
    }

    @Test
    void draftLifecycleCreateRegisterResumeOverwriteDiscard(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // 1. Create: the guided editor's first write — schema-less, inactive, minimal.
            String draft = """
                    {"type":"pipeline","config":{
                       "name":"orders_feed",
                       "description":"orders drop from the ERP",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            HttpResponse<String> w = post(c.port, "/config/write", draft);
            assertEquals(200, w.statusCode(), w.body());
            String path = JSON.readTree(w.body()).get("path").asText();

            // 2. Register so the running service indexes it (write alone is not enough).
            HttpResponse<String> reg = post(c.port, "/runs", "{\"configPath\":\"" + path + "\"}");
            assertEquals(2, reg.statusCode() / 100, "schema-less inactive draft registers: " + reg.body());

            // 3. The draft is catalog-visible immediately, as a Draft (active:false).
            JsonNode streams = JSON.readTree(get(c.port, "/api/v1/catalog/streams").body()).get("data");
            JsonNode draftRow = null;
            for (JsonNode n : streams)
                if ("orders_feed".equals(n.get("attrs").get("pipeline").asText())) draftRow = n;
            assertNotNull(draftRow, "draft appears in /catalog/streams: " + streams);
            assertFalse(draftRow.get("attrs").get("active").asBoolean(), "listed as a draft");

            // 4. Resume: read the config back (decoded), exactly as written.
            HttpResponse<String> r = get(c.port, "/config/pipeline/orders_feed");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode read = JSON.readTree(r.body());
            assertEquals("orders_feed", read.get("name").asText());
            assertEquals("orders_feed", read.get("config").get("name").asText());
            assertEquals("in", read.get("config").get("dirs").get("poll").asText());
            assertTrue(read.get("config").path("parsing").isMissingNode(), "no parsing stage yet");

            // 5. Stage save: overwrite with a parsing block attached.
            String withParsing = """
                    {"type":"pipeline","overwrite":true,"config":{
                       "name":"orders_feed",
                       "description":"orders drop from the ERP",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1},
                       "parsing":{"frontend":"delimited","delimited":{"delimiter":"|","has_header":true}}}}""";
            HttpResponse<String> w2 = post(c.port, "/config/write", withParsing);
            assertEquals(200, w2.statusCode(), w2.body());
            assertTrue(JSON.readTree(w2.body()).get("overwritten").asBoolean());
            JsonNode reread = JSON.readTree(get(c.port, "/config/pipeline/orders_feed").body());
            assertEquals("delimited", reread.get("config").get("parsing").get("frontend").asText());

            // 6. Discard: an inactive draft deletes cleanly.
            assertEquals(200, delete(c.port, "/config/pipeline/orders_feed").statusCode());
            assertEquals(404, get(c.port, "/config/pipeline/orders_feed").statusCode(), "gone after discard");
        }
    }

    @Test
    void readDisabledWithoutWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, get(c.port, "/config/pipeline/anything").statusCode());
        }
    }

    @Test
    void readUnknownTypeIs404AndSpecRouteIsNotShadowed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/nonsense/x").statusCode());
            HttpResponse<String> spec = get(c.port, "/config/spec/pipeline");
            assertEquals(200, spec.statusCode(), "/config/spec/{type} still served: " + spec.body());
        }
    }

    @Test
    void readMissingFileIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, get(c.port, "/config/pipeline/ghost").statusCode());
        }
    }

    @Test
    void parsingPreviewParsesASampleStatelessly(@TempDir Path cfg) throws Exception {
        // No write root on purpose: the preview is stateless compute, not a config mutation.
        try (Ctx c = open(cfg, null)) {
            String body = """
                    {"config":{
                       "name":"p","dirs":{"poll":"in","database":"out"},"processing":{"threads":1},
                       "parsing":{"frontend":"delimited","delimited":{"delimiter":"|","has_header":true}}},
                     "sample_text":"id|city\\n1|london\\n2|paris\\n"}""";
            HttpResponse<String> r = post(c.port, "/config/preview/parsing", body);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("delimited", out.get("frontend").asText());
            assertEquals(2, out.get("rowCount").asInt());
            assertEquals("id", out.get("columns").get(0).asText());
            assertEquals("london", out.get("rows").get(0).get("city").asText());
        }
    }

    @Test
    void parsingPreviewGates400And422(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(400, post(c.port, "/config/preview/parsing",
                    "{\"config\":{\"name\":\"p\"}}").statusCode(), "missing sample_text");
            HttpResponse<String> bad = post(c.port, "/config/preview/parsing",
                    "{\"config\":{\"name\":\"p\"},\"sample_text\":\"x\"}");
            assertEquals(422, bad.statusCode(), "draft without dirs/processing does not parse: " + bad.body());
        }
    }
}
