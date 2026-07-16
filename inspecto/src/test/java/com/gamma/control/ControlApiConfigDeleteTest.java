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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code DELETE /config/{type}/{name}} (v5.1.0, the onboarding draft-discard
 * path) over real HTTP. Covers the fail-closed gate ordering — write-root disabled 503 → unknown
 * type 404 → unsafe name 422 → subdir path jail 403 → missing file 404 → active pipeline 409 —
 * plus the happy path (a written draft is deleted; a second delete 404s) and the non-pipeline
 * types, which have no active gate.
 */
class ControlApiConfigDeleteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** A registered pipeline that is armed — deleting its file must be refused. */
    private static final String ACTIVE_TOON = """
            name: livepipe
            active: true
            dirs:
              poll: in
              database: out
            processing:
              threads: 1
            """;

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

    private HttpResponse<String> delete(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .DELETE().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, delete(c.port, "/config/pipeline/anything").statusCode(),
                    "no -Dassist.write.root ⇒ deletes disabled");
        }
    }

    @Test
    void unknownTypeIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, delete(c.port, "/config/nonsense/x").statusCode());
        }
    }

    @Test
    void unsafeNameIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, delete(c.port, "/config/pipeline/a..b").statusCode(),
                    "a name containing '..' is rejected");
        }
    }

    @Test
    void escapingSubdirIs403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(403, delete(c.port, "/config/pipeline/x?subdir=../escape").statusCode(),
                    "subdir escaping the write root is blocked");
        }
    }

    @Test
    void missingFileIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, delete(c.port, "/config/pipeline/ghost").statusCode());
        }
    }

    @Test
    void activePipelineIs409AndKept(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("livepipe.toon"), ACTIVE_TOON);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = delete(c.port, "/config/pipeline/livepipe");
            assertEquals(409, r.statusCode(), r.body());
            assertTrue(Files.exists(root.resolve("livepipe.toon")), "active pipeline file is kept");
        }
    }

    @Test
    void deletesAWrittenDraftThenSecondDelete404s(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String draft = """
                    {"type":"pipeline","config":{
                       "name":"draft_stream",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(200, post(c.port, "/config/write", draft).statusCode(), "draft written");
            // Written under the bootstrap-scan convention (*_pipeline.toon) so a registered
            // draft survives a service restart; read/delete resolve the same convention.
            assertTrue(Files.exists(root.resolve("draft_stream_pipeline.toon")));

            HttpResponse<String> r = delete(c.port, "/config/pipeline/draft_stream");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = JSON.readTree(r.body());
            assertTrue(out.get("deleted").asBoolean());
            assertEquals("draft_stream_pipeline.toon", out.get("path").asText());
            assertFalse(Files.exists(root.resolve("draft_stream_pipeline.toon")), "file removed");

            assertEquals(404, delete(c.port, "/config/pipeline/draft_stream").statusCode(),
                    "second delete finds nothing");
        }
    }

    @Test
    void nonPipelineTypesHaveNoActiveGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("notes.toon"), "name: notes\n");
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = delete(c.port, "/config/meta/notes");
            assertEquals(200, r.statusCode(), r.body());
            assertFalse(Files.exists(root.resolve("notes.toon")));
        }
    }
}
