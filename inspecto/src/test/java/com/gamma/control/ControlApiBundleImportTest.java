package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips a data-source bundle over real HTTP: export from one space, import into another, and prove
 * conflict handling — a clashing re-import 409s, and {@code ?on_conflict=overwrite} replaces.
 */
class ControlApiBundleImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }
    }

    private Ctx open(Path root) throws Exception {
        Path config = root.resolve("alpha").resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.createDirectories(root.resolve("beta").resolve("config"));   // empty target space

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void exportsFromOneSpaceAndImportsIntoAnotherWithConflictHandling(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(), "beta starts empty");

            // export test_etl from alpha
            byte[] bundle = getBytes(c.port, "/spaces/alpha/datasources/test_etl/export").body();

            // import into beta → the pipeline is registered and live there
            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(200, imp.statusCode(), imp.body());
            assertTrue(JSON.readTree(imp.body()).get("pipelines").toString().contains("test_etl"));
            assertTrue(idList(c.port, "/spaces/beta/datasources").contains("test_etl"),
                    "beta now hosts the imported data source");

            // re-import without overwrite → 409 listing the clash, nothing changes
            HttpResponse<String> clash = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(409, clash.statusCode());
            assertTrue(JSON.readTree(clash.body()).get("conflicts").toString().contains("test_etl"));

            // re-import with overwrite → 200
            assertEquals(200, post(c.port, "/spaces/beta/import?on_conflict=overwrite", bundle).statusCode());
        }
    }

    @Test
    void previewsAnImportWithoutWriting(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = getBytes(c.port, "/spaces/alpha/datasources/test_etl/export").body();

            HttpResponse<String> pv = post(c.port, "/spaces/beta/import/preview", bundle);
            assertEquals(200, pv.statusCode(), pv.body());
            JsonNode r = JSON.readTree(pv.body());
            assertTrue(r.get("dataSources").toString().contains("test_etl"), "lists the bundled data source");
            assertTrue(r.get("conflicts").isEmpty(), "no clash in empty beta");
            assertTrue(r.get("valid").asBoolean(), "the exported pipeline validates: " + r.get("findings"));

            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(), "preview wrote nothing");
        }
    }

    @Test
    void createsANewSpaceFromAWholeSpaceBundle(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] spaceBundle = getBytes(c.port, "/spaces/alpha/export").body();

            HttpResponse<String> created = post(c.port, "/spaces/import?id=gamma", spaceBundle);
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("gamma", JSON.readTree(created.body()).get("id").asText());

            // the new space is hosted and carries alpha's data source
            JsonNode spaces = JSON.readTree(getBytes(c.port, "/spaces").body());
            boolean hasGamma = false;
            for (JsonNode s : spaces) if ("gamma".equals(s.get("id").asText())) hasGamma = true;
            assertTrue(hasGamma, "gamma is now hosted");
            assertTrue(idList(c.port, "/spaces/gamma/datasources").contains("test_etl"),
                    "the bundled data source booted in the new space");

            // a clashing id → 409
            assertEquals(409, post(c.port, "/spaces/import?id=gamma", spaceBundle).statusCode());
            // a missing/invalid id → 400
            assertEquals(400, post(c.port, "/spaces/import", spaceBundle).statusCode());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private java.util.List<String> idList(int port, String path) throws Exception {
        JsonNode arr = JSON.readTree(getBytes(port, path).body());
        java.util.List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private HttpResponse<byte[]> getBytes(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> post(int port, String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/zip")
                .POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }
}
