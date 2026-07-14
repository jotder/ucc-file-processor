package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.metrics.MetricRegistry;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests over real HTTP for the Acquisition/Sources UI backend: {@code GET /collectors} (flattened source
 * config), {@code GET /metrics/acquisition} (JSON metric snapshot), and the connection CRUD
 * ({@code POST/PUT/DELETE /connections}) write-back loop — including the {@code -Dassist.write.root} gate, masked-
 * secret preservation on update, and the in-use delete guard.
 */
class ControlApiCollectorsAndConnectionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server over a pipeline whose source binds to connection {@code warehouse}. writeRoot==null ⇒ writes off. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = connPipeline(configDir, "warehouse");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);   // writeRoot captured in the constructor
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A minimal valid pipeline whose {@code source} binds to a connection id (reusing the mini schema). */
    private Path connPipeline(Path dir, String connId) throws Exception {
        PipelineConfigBatchTest.writePipeline(dir, "");          // creates dir/mini_schema.toon
        Path schema = dir.resolve("mini_schema.toon");
        String toon = """
            name: CONN_ETL
            version: 1
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              temp: %1$s/temp
              errors: %1$s/errors
              status_dir: %1$s/status
              log_dir: %1$s/logs
            collector:
              connector: db
              connection: %2$s
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%3$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, connId, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("conn_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        HttpRequest.BodyPublisher pub = body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body);
        return client.send(b.method(method, pub).build(), BodyHandlers.ofString());
    }

    // ── GET /collectors ────────────────────────────────────────────────────────────

    @Test
    void sourcesEndpointFlattensSourceConfig(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = send(c.port, "GET", "/collectors", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode arr = JSON.readTree(r.body());
            assertTrue(arr.isArray() && arr.size() >= 1, "one row per pipeline source");
            JsonNode row = arr.get(0);
            assertEquals("conn_etl", row.get("pipeline").asText());   // registry normalises the name
            assertEquals("db", row.get("connector").asText());
            assertEquals("warehouse", row.get("connection").asText());
            assertEquals("path", row.get("duplicateMode").asText());      // default
            assertEquals("BEST_EFFORT", row.get("guarantee").asText());   // default
            assertTrue(row.has("dbWatermarkCurrent"), "carries the (null) row-level watermark slot");
        }
    }

    // ── GET /metrics/acquisition ─────────────────────────────────────────────────

    @Test
    void acquisitionMetricsReturnsFilteredJsonSnapshot(@TempDir Path cfg) throws Exception {
        MetricRegistry.global().inc("inspecto_files_discovered_total", "files discovered",
                Map.of("connector", "sftp"), 3);
        MetricRegistry.global().inc("test_non_acquisition_total", "unrelated", Map.of());
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = send(c.port, "GET", "/metrics/acquisition", null);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode m = JSON.readTree(r.body());
            JsonNode disc = m.get("inspecto_files_discovered_total");
            assertNotNull(disc, "acquisition metric present");
            assertEquals("counter", disc.get("type").asText());
            assertTrue(disc.get("series").get(0).get("value").asDouble() >= 3.0);
            assertFalse(m.has("test_non_acquisition_total"), "non-acquisition metric is filtered out");
        }
    }

    // ── connection CRUD ──────────────────────────────────────────────────────────

    private static String conn(String id, String host) {
        return """
            {"id":"%s","connector":"sftp","host":"%s","port":22,"username":"svc","password":"${ENV:SFTP_PW}"}
            """.formatted(id, host);
    }

    @Test
    void crudRoundTripPreservesMaskedSecret(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // create
            assertEquals(200, send(c.port, "POST", "/connections", conn("spare", "h1.example")).statusCode());
            assertTrue(Files.exists(root.resolve("spare_connection.toon")), "persisted under the write root");

            // get — the ${ENV:…} reference is shown verbatim (not masked)
            JsonNode got = JSON.readTree(send(c.port, "GET", "/connections/spare", null).body());
            assertEquals("h1.example", got.get("host").asText());
            assertEquals("${ENV:SFTP_PW}", got.get("password").asText());

            // update with the mask sentinel for the secret → the stored reference is preserved
            String upd = """
                {"id":"spare","connector":"sftp","host":"h2.example","port":22,"username":"svc","password":"***"}
                """;
            assertEquals(200, send(c.port, "PUT", "/connections/spare", upd).statusCode());
            JsonNode after = JSON.readTree(send(c.port, "GET", "/connections/spare", null).body());
            assertEquals("h2.example", after.get("host").asText(), "host updated");
            assertEquals("${ENV:SFTP_PW}", after.get("password").asText(), "masked secret preserved, not clobbered");

            // delete (not in use) → removed
            assertEquals(200, send(c.port, "DELETE", "/connections/spare", null).statusCode());
            assertFalse(Files.exists(root.resolve("spare_connection.toon")), "file removed");
            assertEquals(404, send(c.port, "GET", "/connections/spare", null).statusCode());
        }
    }

    @Test
    void duplicateCreateIs409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c.port, "POST", "/connections", conn("dup", "h")).statusCode());
            assertEquals(409, send(c.port, "POST", "/connections", conn("dup", "h")).statusCode(),
                    "creating an existing id is a conflict");
        }
    }

    @Test
    void deleteInUseConnectionIs409(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // the CONN_ETL pipeline's source binds to "warehouse"
            assertEquals(200, send(c.port, "POST", "/connections", conn("warehouse", "wh.example")).statusCode());
            assertEquals(409, send(c.port, "DELETE", "/connections/warehouse", null).statusCode(),
                    "a connection a pipeline source uses cannot be deleted");
        }
    }

    @Test
    void crudDisabledWithoutWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            assertEquals(503, send(c.port, "POST", "/connections", conn("x", "h")).statusCode(),
                    "no -Dassist.write.root ⇒ connection writes disabled");
        }
    }
}
