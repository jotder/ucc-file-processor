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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests over real HTTP for the read-only flow-graph projection (doc §6, T31): the
 * {@code GET /flows} list, the {@code GET /flows/node-types} editor palette, and
 * {@code GET /flows/{id}/graph} (a registered pipeline lifted to a {@code FlowGraph} and projected).
 */
class ControlApiFlowsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = pipeline(dir);
        SourceService svc = new SourceService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** A minimal single-schema pipeline (reuses the mini schema); read-only projection needs no run/active gate. */
    private Path pipeline(Path dir) throws Exception {
        PipelineConfigBatchTest.writePipeline(dir, "");          // creates dir/mini_schema.toon
        Path schema = dir.resolve("mini_schema.toon");
        String toon = """
            name: FLOW_ETL
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              temp: %1$s/temp
              errors: %1$s/errors
              status_dir: %1$s/status
              log_dir: %1$s/logs
            source:
              connector: db
              connection: warehouse
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("flow_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void flowsListProjectsRegisteredPipelines(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/flows");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode arr = JSON.readTree(r.body());
            assertTrue(arr.isArray() && arr.size() >= 1, "one entry per registered pipeline");
            JsonNode row = arr.get(0);
            assertEquals("flow_etl", row.get("name").asText());   // registry normalises the name
            assertTrue(row.get("nodeCount").asInt() >= 4);
            assertTrue(row.get("produces").isArray() && row.get("produces").size() >= 1);
        }
    }

    @Test
    void nodeTypesCatalogIsServed(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode arr = JSON.readTree(get(c.port, "/flows/node-types").body());
            assertTrue(arr.isArray());
            boolean hasView = false, hasAcq = false;
            for (JsonNode t : arr) {
                if ("sink.view".equals(t.get("type").asText())) {
                    hasView = true;
                    assertEquals("SINK", t.get("category").asText());
                }
                if ("acquisition".equals(t.get("type").asText())) {
                    hasAcq = true;
                    assertEquals("SOURCE", t.get("category").asText());
                }
            }
            assertTrue(hasView && hasAcq, "palette carries sink.view + acquisition with their categories");
        }
    }

    @Test
    void flowGraphProjectionRendersNodesAndEdges(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/flows/flow_etl/graph");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode g = JSON.readTree(r.body());
            assertEquals("flow_etl", g.get("name").asText());
            assertTrue(g.get("nodes").isArray() && g.get("nodes").size() >= 4);
            assertTrue(g.get("edges").isArray() && g.get("edges").size() >= 3);
            boolean persistentSink = false;
            for (JsonNode n : g.get("nodes")) {
                if ("sink.persistent".equals(n.get("type").asText())) {
                    persistentSink = true;
                    assertEquals("persistent", n.get("sinkKind").asText());
                    assertTrue(n.get("restsOnDisk").asBoolean());
                }
            }
            assertTrue(persistentSink, "graph carries a persistent sink");
            assertTrue(g.get("produces").size() >= 1);
        }
    }

    @Test
    void unknownFlowIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, "/flows/nope/graph").statusCode());
        }
    }
}
