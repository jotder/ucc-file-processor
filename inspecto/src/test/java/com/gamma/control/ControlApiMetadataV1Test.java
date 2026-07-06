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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for W3 versioned metadata: the widened {@code ComponentStore} (a {@code widget}
 * now persists for real), the strong {@code ETag} = content hash on a metadata GET/PUT,
 * {@code If-None-Match} → 304, {@code If-Match} → 409 {@code CONFLICT_STALE_VERSION}, and the
 * {@code GET /api/v1/bootstrap} aggregate. Exercised on the {@code /api/v1} surface so the envelope
 * and the ETag coexist.
 */
class ControlApiMetadataV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            SourceService svc = new SourceService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpRequest.Builder req(int port, String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        return b;
    }

    // ── widened store + ETag lifecycle ────────────────────────────────────────────

    @Test
    void widgetPersistsWithVersionMetadataAndConditionalRequests(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // CREATE — widget is now a WRITABLE_TYPE (was mock-only before W3).
            HttpResponse<String> created = client.send(
                    req(c.port, "/api/v1/components/widget")
                            .method("POST", BodyPublishers.ofString("{\"id\":\"sales\",\"kind\":\"bar\",\"title\":\"Sales\"}"))
                            .build(),
                    BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
            JsonNode data = JSON.readTree(created.body()).get("data");
            assertEquals("widget", data.get("type").asText());
            assertFalse(data.get("contentHash").asText().isBlank(), "W3: version metadata present");
            assertTrue(data.has("created") && data.has("modified"));
            String postEtag = created.headers().firstValue("ETag").orElse(null);
            assertNotNull(postEtag, "the write response carries the new ETag");
            assertEquals("\"sha256:" + data.get("contentHash").asText() + "\"", postEtag,
                    "ETag is the content hash");

            // READ — same ETag; body is enveloped with version metadata.
            HttpResponse<String> got = client.send(req(c.port, "/api/v1/components/widget/sales").GET().build(),
                    BodyHandlers.ofString());
            assertEquals(200, got.statusCode());
            String etag = got.headers().firstValue("ETag").orElse(null);
            assertEquals(postEtag, etag, "read ETag matches the written one (persisted, re-scanned from disk)");

            // If-None-Match → 304, no body.
            HttpResponse<String> fresh = client.send(
                    req(c.port, "/api/v1/components/widget/sales", "If-None-Match", etag).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(304, fresh.statusCode());
            assertTrue(fresh.body().isEmpty(), "304 carries no body");
            assertEquals(etag, fresh.headers().firstValue("ETag").orElse(null));

            // If-Match stale → 409 CONFLICT_STALE_VERSION.
            HttpResponse<String> stale = client.send(
                    req(c.port, "/api/v1/components/widget/sales", "If-Match", "\"sha256:deadbeef\"")
                            .method("PUT", BodyPublishers.ofString("{\"kind\":\"line\",\"title\":\"Sales\"}"))
                            .build(),
                    BodyHandlers.ofString());
            assertEquals(409, stale.statusCode());
            assertEquals("CONFLICT_STALE_VERSION",
                    JSON.readTree(stale.body()).get("error").get("errorCode").asText());

            // If-Match current → 200, new ETag differs.
            HttpResponse<String> ok = client.send(
                    req(c.port, "/api/v1/components/widget/sales", "If-Match", etag)
                            .method("PUT", BodyPublishers.ofString("{\"kind\":\"line\",\"title\":\"Sales\"}"))
                            .build(),
                    BodyHandlers.ofString());
            assertEquals(200, ok.statusCode(), ok.body());
            assertNotEquals(etag, ok.headers().firstValue("ETag").orElse(null),
                    "a real content change yields a new ETag");
        }
    }

    // ── bootstrap ──────────────────────────────────────────────────────────────────

    @Test
    void bootstrapAggregatesAndCaches(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = client.send(req(c.port, "/api/v1/bootstrap").GET().build(),
                    BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals("personal", data.get("edition").asText(), "auth-free core reports Personal");
            assertTrue(data.get("features").get("authoring").asBoolean(), "write root set ⇒ authoring on");
            // every config spec folded into one call
            JsonNode specs = data.get("configSpecs");
            for (String type : List.of("pipeline", "enrichment", "job", "schema", "meta", "alert"))
                assertTrue(specs.has(type), "bootstrap carries the '" + type + "' spec");
            assertTrue(data.get("enumerations").get("severities").isArray());
            assertTrue(data.get("spaces").isArray());
            assertFalse(data.get("session").get("authenticated").asBoolean(), "auth-free core");

            String etag = r.headers().firstValue("ETag").orElse(null);
            assertNotNull(etag, "bootstrap is ETag'd (cacheable metadata)");
            HttpResponse<String> cached = client.send(
                    req(c.port, "/api/v1/bootstrap", "If-None-Match", etag).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(304, cached.statusCode(), "unchanged bootstrap ⇒ 304 on the hot path");
        }
    }
}
