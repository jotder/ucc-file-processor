package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.SourceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for the v1 transport spine (worklog W1, docs/superpower/api-contract-design.md
 * §10): the {@code /api/v1} seam + response envelope, the structured error object with contract
 * error codes, per-request {@code Correlation-ID} issue/echo, gzip content negotiation — and that
 * the legacy (unversioned) surface stays byte-for-byte unchanged.
 */
class ControlApiV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled. */
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

    private HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        return client.send(req(port, path, headers).GET().build(), BodyHandlers.ofString());
    }

    @Test
    void v1SuccessIsEnvelopedAndCorrelationIdIssued(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = get(c.port, "/api/v1/health");
            assertEquals(200, r.statusCode());
            JsonNode env = JSON.readTree(r.body());
            assertEquals("UP", env.get("data").get("status").asText(), "handler result under 'data'");
            assertEquals("v1", env.get("metadata").get("apiVersion").asText());
            assertTrue(env.get("metadata").get("durationMs").asLong() >= 0);
            assertFalse(env.get("metadata").get("timestamp").asText().isBlank());
            assertEquals("/api/v1/health", env.get("links").get("self").asText());
            String cid = env.get("diagnostics").get("correlationId").asText();
            assertFalse(cid.isBlank(), "a correlation id is issued when the caller sends none");
            assertEquals(cid, r.headers().firstValue("Correlation-ID").orElse(null),
                    "the same id rides the response header");
        }
    }

    @Test
    void correlationIdEchoedAndLegacyBodyUnchanged(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = get(c.port, "/health", "Correlation-ID", "shift-cid-42");
            assertEquals(200, r.statusCode());
            assertEquals("shift-cid-42", r.headers().firstValue("Correlation-ID").orElse(null),
                    "a caller-supplied id is honoured and echoed — on the legacy surface too");
            JsonNode body = JSON.readTree(r.body());
            assertEquals("UP", body.get("status").asText(), "legacy body is the raw handler result");
            assertNull(body.get("data"), "no envelope on the unversioned surface");
        }
    }

    @Test
    void v1NotFoundIsStructuredError(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = get(c.port, "/api/v1/no-such-route");
            assertEquals(404, r.statusCode());
            JsonNode err = JSON.readTree(r.body()).get("error");
            assertEquals("NOT_FOUND", err.get("errorCode").asText());
            assertEquals("not found", err.get("message").asText());
            assertTrue(err.get("recoverable").asBoolean());
            assertFalse(err.get("correlationId").asText().isBlank());
        }
    }

    @Test
    void v1MethodNotAllowedCode(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = client.send(req(c.port, "/api/v1/health").DELETE().build(),
                    BodyHandlers.ofString());
            assertEquals(405, r.statusCode());
            assertEquals("METHOD_NOT_ALLOWED",
                    JSON.readTree(r.body()).get("error").get("errorCode").asText());
        }
    }

    @Test
    void v1WriteRootGateHasContractCode(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = client.send(
                    req(c.port, "/api/v1/config/write")
                            .method("POST", BodyPublishers.ofString("{\"type\":\"pipeline\",\"config\":{\"name\":\"x\"}}"))
                            .build(),
                    BodyHandlers.ofString());
            assertEquals(503, r.statusCode());
            JsonNode err = JSON.readTree(r.body()).get("error");
            assertEquals("CONTROL_PLANE_READ_ONLY", err.get("errorCode").asText(),
                    "the write-root gate carries its specific contract code, not the 503 default");
            assertEquals("config write disabled: set -Dassist.write.root to enable",
                    err.get("message").asText());
        }
    }

    @Test
    void legacyWriteRootErrorUnchanged(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = client.send(
                    req(c.port, "/config/write")
                            .method("POST", BodyPublishers.ofString("{\"type\":\"pipeline\",\"config\":{\"name\":\"x\"}}"))
                            .build(),
                    BodyHandlers.ofString());
            assertEquals(503, r.statusCode());
            assertEquals("{\"error\":\"config write disabled: set -Dassist.write.root to enable\"}",
                    r.body(), "legacy error shape is byte-for-byte unchanged");
        }
    }

    @Test
    void v1Validation422CarriesFindingsInDetails(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // ingester set but no segments → the plugin-ingester-requires-segments ERROR finding.
            String bad = """
                    {"type":"pipeline","config":{
                       "name":"broken","dirs":{"poll":"in","database":"out"},
                       "processing":{"ingester":"com.x.Plugin","threads":1}}}""";
            HttpResponse<String> r = client.send(
                    req(c.port, "/api/v1/config/write").method("POST", BodyPublishers.ofString(bad)).build(),
                    BodyHandlers.ofString());
            assertEquals(422, r.statusCode());
            JsonNode err = JSON.readTree(r.body()).get("error");
            assertEquals("CONFIG_VALIDATION_FAILED", err.get("errorCode").asText());
            assertTrue(err.get("details").get("findings").isArray(),
                    "the findings payload is preserved under error.details");
            assertFalse(err.get("details").get("written").asBoolean());
        }
    }

    @Test
    void gzipNegotiatedOnLargeJsonBodies(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> plain = get(c.port, "/config/spec/pipeline");
            assertEquals(200, plain.statusCode());
            assertTrue(plain.headers().firstValue("Content-Encoding").isEmpty(),
                    "no compression without Accept-Encoding");
            assertTrue(plain.body().length() >= ApiContext.GZIP_MIN_BYTES,
                    "fixture sanity: the pipeline spec is large enough to trigger compression");

            HttpResponse<byte[]> zipped = client.send(
                    req(c.port, "/config/spec/pipeline", "Accept-Encoding", "gzip").GET().build(),
                    BodyHandlers.ofByteArray());
            assertEquals(200, zipped.statusCode());
            assertEquals("gzip", zipped.headers().firstValue("Content-Encoding").orElse(null));
            byte[] inflated;
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped.body()))) {
                inflated = in.readAllBytes();
            }
            assertEquals(JSON.readTree(plain.body()), JSON.readTree(inflated),
                    "gzipped body inflates to the identical JSON");
        }
    }
}
