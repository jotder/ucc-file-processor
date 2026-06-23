package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Stage-6 per-space data-source export endpoints over real HTTP: list data sources, download one
 * data source's bundle zip, and download a whole-space zip — all routed through the {@code /spaces/{id}/}
 * seam to the bound space.
 */
class ControlApiDataSourceExportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();   // drop this fork's space-labelled series (see ControlApiMultiSpaceTest)
        }
    }

    private Ctx open(Path root) throws Exception {
        Path base   = root.resolve("alpha");
        Path config = base.resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));                       // dir-scan finds *_pipeline.toon
        Files.writeString(config.resolve("etl_job.toon"),
                "job:\n  name: etl_heartbeat\n  type: maintenance\n  task: heartbeat\n  on_pipeline: test_etl\n");
        Files.writeString(base.resolve("space.toon"),
                "display_name: \"Alpha\"\ndescription: \"x\"\ncreated_at: \"2026-06-23\"\n");

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void listsExportsADataSourceBundleAndAWholeSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // ── list ──
            JsonNode list = JSON.readTree(sendText(c.port, "/spaces/alpha/datasources").body());
            assertTrue(list.isArray() && list.size() == 1 && "test_etl".equals(list.get(0).asText()),
                    "the one pipeline is listed as a data source (lowercased name)");

            // ── selective export: a data-source bundle zip ──
            HttpResponse<byte[]> ds = sendBytes(c.port, "/spaces/alpha/datasources/test_etl/export");
            assertEquals(200, ds.statusCode());
            assertEquals("application/zip", ds.headers().firstValue("Content-Type").orElse(""));
            Map<String, byte[]> bundle = unzip(ds.body());
            assertTrue(bundle.containsKey("bundle.toon"), "manifest present");
            assertTrue(bundle.containsKey("etl_pipeline.toon"), "the pipeline travels in the bundle");
            assertTrue(bundle.containsKey("etl_job.toon"), "the job whose on_pipeline targets it travels too");
            JsonNode manifest = manifest(bundle.get("bundle.toon"), root);
            assertEquals("datasource", manifest.get("kind").asText());
            assertEquals("alpha", manifest.get("source_space").asText());
            assertEquals("test_etl", manifest.get("data_source").asText());

            // ── unknown data source → 404 ──
            assertEquals(404, sendBytes(c.port, "/spaces/alpha/datasources/nope/export").statusCode());

            // ── whole-space export zip ──
            HttpResponse<byte[]> space = sendBytes(c.port, "/spaces/alpha/export");
            assertEquals(200, space.statusCode());
            assertEquals("application/zip", space.headers().firstValue("Content-Type").orElse(""));
            Map<String, byte[]> whole = unzip(space.body());
            assertTrue(whole.containsKey("bundle.toon"));
            assertTrue(whole.containsKey("space.toon"), "the whole-space zip carries the space manifest");
            assertEquals("space", manifest(whole.get("bundle.toon"), root).get("kind").asText());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private HttpResponse<String> sendText(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("GET", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> sendBytes(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("GET", BodyPublishers.noBody()).build(), BodyHandlers.ofByteArray());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) out.put(e.getName(), zis.readAllBytes());
        }
        return out;
    }

    private static JsonNode manifest(byte[] toonBytes, Path tmp) throws Exception {
        Path mf = tmp.resolve("mf-" + System.nanoTime() + ".toon");
        Files.write(mf, toonBytes);
        return JSON.valueToTree(com.gamma.util.ToonHelper.load(mf.toString()));
    }
}
