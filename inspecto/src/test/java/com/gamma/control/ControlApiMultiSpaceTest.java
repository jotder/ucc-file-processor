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
 * Cross-space isolation proof for the Stage-4 {@code /spaces/{id}} request seam, over real HTTP. Two spaces host the
 * <em>same</em> pipeline id ({@code TEST_ETL}); the seam must route each request to its own {@code SourceService} so
 * audit/commits, events and the {@code space}-labelled metrics never bleed across spaces, while an unknown id 404s and
 * the un-prefixed {@code /health}/{@code /metrics} stay server-global. The companion unit tests cover the per-space
 * isolation of each singleton in isolation ({@code ConnectionRegistryTest}, {@code MetricRegistryTest}, …); this test
 * proves the HTTP boundary binds them to the right space end-to-end.
 */
class ControlApiMultiSpaceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV = "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            // The metric registry is a process-wide singleton: drop this test's space-labelled series so the
            // bare-substring poll in ControlApiTest's metrics test isn't tripped by them in the same JVM fork.
            MetricRegistry.global().reset();
        }
    }

    /** Boot a two-space container ("alpha"/"beta"), each with the same TEST_ETL pipeline, behind one ControlApi. */
    private Ctx open(Path root) throws Exception {
        seedSpace(root, "alpha");
        seedSpace(root, "beta");
        SpaceManager spaces = SpaceManager.discover(root);
        assertEquals(2, spaces.size(), "both spaces booted");
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();   // wires each space's MetricsService + runs one (empty-inbox) cycle
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    /** A space dir with a {@code config/} subtree holding one TEST_ETL pipeline (renamed to the *_pipeline.toon suffix). */
    private void seedSpace(Path root, String id) throws Exception {
        Path config = root.resolve(id).resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));   // dir-scan discovers *_pipeline.toon
        Files.createDirectories(config.resolve("inbox"));        // empty at boot; data dropped per assertion
    }

    private Path inbox(Path root, String id) { return root.resolve(id).resolve("config").resolve("inbox"); }

    @Test
    void seamRoutesToTheRightSpaceAndIsolatesAuditAndMetrics(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            // ── the seam routes each space's reads to its own service ──
            JsonNode alpha = json(send(c.port, "GET", "/spaces/alpha/runs", null));
            JsonNode beta  = json(send(c.port, "GET", "/spaces/beta/runs", null));
            assertTrue(alpha.isArray() && alpha.size() == 1 && "test_etl".equals(alpha.get(0).get("name").asText()));
            assertTrue(beta.isArray()  && beta.size()  == 1 && "test_etl".equals(beta.get(0).get("name").asText()));

            // unknown space → 404; un-prefixed server-global routes still serve
            assertEquals(404, send(c.port, "GET", "/spaces/ghost/runs", null).statusCode());
            assertEquals(200, send(c.port, "GET", "/health", null).statusCode());

            // ── trigger alpha through the seam; only alpha sees the batch ──
            Files.writeString(inbox(root, "alpha").resolve("data.csv"), CSV);
            HttpResponse<String> run = send(c.port, "POST", "/spaces/alpha/runs/test_etl/trigger", null);
            assertEquals(200, run.statusCode(), run.body());
            assertTrue(json(run).get("total").asInt() >= 1, "alpha processed its file");

            assertFalse(json(send(c.port, "GET", "/spaces/alpha/runs/test_etl/commits", null)).isEmpty(),
                    "alpha committed a batch");
            assertTrue(json(send(c.port, "GET", "/spaces/beta/runs/test_etl/commits", null)).isEmpty(),
                    "beta's audit store saw none of alpha's commits");

            // metrics: alpha's batch counter carries space="alpha"; no beta-labelled batch counter exists yet
            String metrics = awaitMetric(c.port, "inspecto_batches_total{pipeline=\"test_etl\",space=\"alpha\"");
            assertFalse(metrics.contains("inspecto_batches_total{pipeline=\"test_etl\",space=\"beta\""),
                    "beta has run no batch — its batch counter must not appear:\n" + metrics);

            // ── trigger beta too; now both labels exist, still partitioned ──
            Files.writeString(inbox(root, "beta").resolve("data.csv"), CSV);
            assertEquals(200, send(c.port, "POST", "/spaces/beta/runs/test_etl/trigger", null).statusCode());
            assertFalse(json(send(c.port, "GET", "/spaces/beta/runs/test_etl/commits", null)).isEmpty(),
                    "beta now has its own committed batch");
            String both = awaitMetric(c.port, "inspecto_batches_total{pipeline=\"test_etl\",space=\"beta\"");
            assertTrue(both.contains("inspecto_batches_total{pipeline=\"test_etl\",space=\"alpha\""),
                    "alpha's batch counter is still present and distinct");
        }
    }

    /** Poll {@code /metrics} until {@code needle} appears (batch events deliver synchronously, but allow slack). */
    private String awaitMetric(int port, String needle) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        String body = "";
        while (System.nanoTime() < deadline) {
            body = send(port, "GET", "/metrics", null).body();
            if (body.contains(needle)) return body;
            Thread.sleep(100);
        }
        fail("metric not found within timeout: " + needle + "\n" + body);
        return body;
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return JSON.readTree(r.body()); }
}
