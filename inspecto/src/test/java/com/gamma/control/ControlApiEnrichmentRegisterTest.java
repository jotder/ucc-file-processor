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
 * Integration tests for {@code POST /enrichment} (v5.1.0) over real HTTP — the Stage-2 half of the
 * onboarding authoring pair: {@code POST /config/write type=enrichment} persists the TOON (with
 * the {@code *_enrich.toon} boot-scan suffix), then {@code POST /enrichment} hot-registers it on
 * the running service, no restart. Covers every fail-closed gate, the fresh-space case (the
 * service now hosts an empty job list instead of being absent), and replace-by-name upsert.
 */
class ControlApiEnrichmentRegisterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server with one pipeline and NO enrichments. {@code writeRoot==null} ⇒ writes disabled. */
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

    /** A minimal spec-valid enrichment draft (relative dirs keep the safety jail happy). */
    private static String draft(String name, String onPipeline) {
        return """
                {"type":"enrichment","config":{
                   "name":"%s",
                   "input":{"database":"out","format":"PARQUET","partitions":["year","month","day"]},
                   "output":{"database":"enriched/%s","format":"PARQUET","partitions":["year","month","day"]},
                   "transform":"SELECT * FROM input",
                   "triggers":{"on_pipeline":"%s"}}}""".formatted(name, name, onPipeline);
    }

    @Test
    void writesWithScanSuffixThenHotRegistersOnAFreshSpace(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // Fresh space: the service exists with zero jobs (used to be absent → 404).
            HttpResponse<String> before = get(c.port, "/enrichment");
            assertEquals(200, before.statusCode(), before.body());
            assertEquals(0, JSON.readTree(before.body()).size(), "no jobs hosted yet");

            // Author + persist: the filename must carry the *_enrich.toon boot-scan suffix, or
            // the job would silently drop out on the next service restart (the P2 pipeline trap).
            HttpResponse<String> w = post(c.port, "/config/write", draft("orders_daily", "orders_feed"));
            assertEquals(200, w.statusCode(), w.body());
            String path = JSON.readTree(w.body()).get("path").asText();
            assertTrue(path.endsWith("orders_daily_enrich.toon"), "scan-convention filename: " + path);

            // Hot-register — no restart.
            HttpResponse<String> reg = post(c.port, "/enrichment", "{\"configPath\":\"" + path + "\"}");
            assertEquals(200, reg.statusCode(), reg.body());
            JsonNode r = JSON.readTree(reg.body());
            assertTrue(r.get("registered").asBoolean());
            assertEquals("orders_daily", r.get("name").asText());
            assertEquals("orders_feed", r.get("job").get("onPipeline").asText());
            assertTrue(r.get("job").get("eventTriggered").asBoolean());

            // The running service hosts it immediately.
            JsonNode list = JSON.readTree(get(c.port, "/enrichment").body());
            assertEquals(1, list.size());
            assertEquals("orders_daily", list.get(0).get("name").asText());
        }
    }

    @Test
    void reRegisterReplacesByName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String path = JSON.readTree(post(c.port, "/config/write",
                    draft("orders_daily", "orders_feed")).body()).get("path").asText();
            assertEquals(200, post(c.port, "/enrichment", "{\"configPath\":\"" + path + "\"}").statusCode());

            // Stage save: overwrite the file with a different upstream, register again.
            String overwrite = draft("orders_daily", "other_feed")
                    .replace("\"type\":\"enrichment\"", "\"type\":\"enrichment\",\"overwrite\":true");
            assertEquals(200, post(c.port, "/config/write", overwrite).statusCode());
            HttpResponse<String> reg2 = post(c.port, "/enrichment", "{\"configPath\":\"" + path + "\"}");
            assertEquals(200, reg2.statusCode(), reg2.body());

            JsonNode list = JSON.readTree(get(c.port, "/enrichment").body());
            assertEquals(1, list.size(), "replaced, not duplicated: " + list);
            assertEquals("other_feed", list.get(0).get("onPipeline").asText());
        }
    }

    @Test
    void registerGatesFailClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // 503 — write root unset ⇒ registration disabled.
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, post(c.port, "/enrichment", "{\"configPath\":\"x_enrich.toon\"}").statusCode());
        }
        try (Ctx c = open(cfg, root)) {
            // 400 — missing configPath.
            assertEquals(400, post(c.port, "/enrichment", "{}").statusCode());
            // 403 — path escaping the write root.
            assertEquals(403, post(c.port, "/enrichment",
                    "{\"configPath\":\"../outside_enrich.toon\"}").statusCode());
            // 404 — nothing written there.
            assertEquals(404, post(c.port, "/enrichment",
                    "{\"configPath\":\"ghost_enrich.toon\"}").statusCode());

            // 422 — spec ERROR (no transform / transform_file). The write gate blocks such a
            // draft too; drop the file in place directly to prove register re-validates files
            // that never went through POST /config/write.
            java.nio.file.Files.writeString(root.resolve("broken_enrich.toon"), """
                    name: broken
                    input:
                      database: out
                      format: PARQUET
                      partitions[1]: year
                    output:
                      database: enriched/broken
                      format: PARQUET
                    """);
            HttpResponse<String> reg = post(c.port, "/enrichment",
                    "{\"configPath\":\"broken_enrich.toon\"}");
            assertEquals(422, reg.statusCode(), reg.body());
        }
    }
}
