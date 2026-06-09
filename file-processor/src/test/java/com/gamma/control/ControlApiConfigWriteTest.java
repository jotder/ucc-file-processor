package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.io.ConfigLoader;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code POST /config/write} (v4.1.0, scope {@code assist.write}) over real
 * HTTP: the author→save loop that persists a validated config draft to a {@code .toon} file under
 * the {@code -Dassist.write.root} jail. Covers the fail-closed gate ordering (writes disabled →
 * scope → safety gate → identity-derived filename → path jail → overwrite policy) and that the
 * written TOON round-trips back off disk.
 */
class ControlApiConfigWriteTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled; {@code tokens==null} ⇒ control-only. */
    private Ctx open(Path configDir, Path writeRoot, ControlApi.Tokens tokens) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            SourceService svc = new SourceService(List.of(pipe), 3600, 1);
            // The write root is read in the constructor, so it is captured here regardless of the
            // clear in finally (which only keeps the JVM-wide property from leaking to other tests).
            ControlApi api = tokens == null
                    ? new ControlApi(svc, 0, TOKEN)
                    : new ControlApi(svc, 0, tokens);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String pipeline(String name) {
        return """
                {"type":"pipeline","config":{
                   "name":"%s",
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1}}}""".formatted(name);
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null, null)) {
            HttpResponse<String> r = post(c.port, "/config/write", TOKEN, pipeline("orders"));
            assertEquals(503, r.statusCode(), "no -Dassist.write.root ⇒ writes disabled");
        }
    }

    @Test
    void writesValidPipelineAndRoundTripsOffDisk(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, null)) {
            HttpResponse<String> r = post(c.port, "/config/write", TOKEN, pipeline("orders_daily"));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertTrue(out.get("written").asBoolean());
            assertEquals("orders_daily", out.get("name").asText());
            assertEquals("orders_daily.toon", out.get("path").asText());
            assertFalse(out.get("overwritten").asBoolean());

            Path written = root.resolve("orders_daily.toon");
            assertTrue(Files.exists(written), "file persisted under the write root");
            // The encoded TOON decodes back to the same config (round-trip correctness).
            Map<String, Object> decoded = ConfigLoader.filesystem().decode(written.toString());
            assertEquals("orders_daily", decoded.get("name"));
        }
    }

    @Test
    void refusesOverwriteByDefaultThenAllowsWithFlag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, null)) {
            assertEquals(200, post(c.port, "/config/write", TOKEN, pipeline("dup")).statusCode());
            assertEquals(409, post(c.port, "/config/write", TOKEN, pipeline("dup")).statusCode(),
                    "existing file refused without overwrite");
            String withFlag = """
                    {"type":"pipeline","overwrite":true,"config":{
                       "name":"dup","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = JSON.readTree(post(c.port, "/config/write", TOKEN, withFlag).body());
            assertTrue(out.get("written").asBoolean());
            assertTrue(out.get("overwritten").asBoolean(), "overwrite:true replaces");
        }
    }

    @Test
    void errorConfigIs422AndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, null)) {
            // ingester set but no segments → the plugin-ingester-requires-segments ERROR finding.
            String bad = """
                    {"type":"pipeline","config":{
                       "name":"broken","dirs":{"poll":"in","database":"out"},
                       "processing":{"ingester":"com.x.Plugin","threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", TOKEN, bad);
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertFalse(out.get("written").asBoolean());
            assertTrue(out.get("findings").size() > 0, "findings returned");
            assertFalse(Files.exists(root.resolve("broken.toon")), "nothing written on a rejected config");
        }
    }

    @Test
    void scopeRequiresAssistWriteNotRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        ControlApi.Tokens tokens = new ControlApi.Tokens(null, "read-only", "write-tok");
        try (Ctx c = open(cfg, root, tokens)) {
            assertEquals(401, post(c.port, "/config/write", null, pipeline("a")).statusCode(),
                    "no token ⇒ 401");
            assertEquals(401, post(c.port, "/config/write", "read-only", pipeline("a")).statusCode(),
                    "assist.read does NOT satisfy assist.write");
            assertEquals(200, post(c.port, "/config/write", "write-tok", pipeline("a")).statusCode(),
                    "assist.write token authorises the write");
        }
    }

    @Test
    void pathJailRejectsEscapingSubdirAndAbsolute(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, null)) {
            String escaping = """
                    {"type":"pipeline","subdir":"../escape","config":{
                       "name":"x","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(403, post(c.port, "/config/write", TOKEN, escaping).statusCode(),
                    "subdir escaping the root is blocked");
            // A rooted subdir: absolute on POSIX (→400 'must be relative'); on Windows "/etc" is
            // drive-relative (isAbsolute()==false) so it falls through to the jail (→403 escapes root).
            // Either way it is blocked from escaping the write root.
            String absolute = """
                    {"type":"pipeline","subdir":"/etc","config":{
                       "name":"x","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            int code = post(c.port, "/config/write", TOKEN, absolute).statusCode();
            assertTrue(code == 400 || code == 403, "rooted subdir is rejected (got " + code + ")");
        }
    }

    @Test
    void unsafeIdentityNameRejected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, null)) {
            String traversalName = """
                    {"type":"pipeline","config":{
                       "name":"../../etc/passwd","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(422, post(c.port, "/config/write", TOKEN, traversalName).statusCode(),
                    "a config name with path separators / .. is rejected");
        }
    }

    @Test
    void writesIntoAJailedSubdir(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, null)) {
            String inSub = """
                    {"type":"pipeline","subdir":"team/etl","config":{
                       "name":"nested","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = JSON.readTree(post(c.port, "/config/write", TOKEN, inSub).body());
            assertEquals("team/etl/nested.toon", out.get("path").asText());
            assertTrue(Files.exists(root.resolve("team").resolve("etl").resolve("nested.toon")));
        }
    }
}
