package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP + real-DuckDB tests for W4 query execution ({@code POST /queries/{id}/run}): a persisted
 * {@code query} component runs against the sandbox with server-side {@code $}-parameter resolution +
 * caller overrides, returns the typed Result Set contract, resolves a dataset via a view, and fails
 * closed (404 unknown query/dataset, 422 non-sql / SqlGuard violation).
 */
class ControlApiQueryRunV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private ComponentStore registry(Ctx c) { return new ComponentStore(c.root.resolve("registry")); }

    private HttpResponse<String> run(int port, String id, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/queries/" + id + "/run"))
                .method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body))
                .build();
        return client.send(req, BodyHandlers.ofString());
    }

    @Test
    void runsParameterisedQueryAndDescribesResultSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            registry(c).write("query", "sales_q", Map.of(
                    "type", "sql",
                    "text", "SELECT * FROM (VALUES (1,'alpha',10.0),(2,'beta',20.0)) AS t(id,label,amount) WHERE amount > $minAmt",
                    "parameters", List.of(Map.of("name", "minAmt", "type", "number", "default", "1"))));

            // default param → both rows
            JsonNode data = JSON.readTree(assertOk(run(c.port, "sales_q", null))).get("data");
            assertEquals(2, data.get("rows").size());
            assertEquals(2, data.get("resultSet").get("rowCount").asInt());
            assertFalse(data.get("statistics").get("truncated").asBoolean());
            assertRole(data, "amount", "measure");
            assertRole(data, "label", "dimension");
            assertRole(data, "id", "dimension");                 // *_id number stays a dimension
            List<String> renderings = JSON.convertValue(data.get("renderings"), List.class);
            assertTrue(renderings.contains("table") && renderings.contains("bar-chart"), renderings.toString());

            // caller override narrows the result
            JsonNode filtered = JSON.readTree(assertOk(run(c.port, "sales_q",
                    "{\"parameters\":{\"minAmt\":\"15\"}}"))).get("data");
            assertEquals(1, filtered.get("rows").size());
            assertEquals("beta", filtered.get("rows").get(0).get("label").asText());
        }
    }

    @Test
    void resolvesDatasetViaView(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES (1,'x',5.0)) AS t(id,label,amount)", "2026-07-06T00:00:00Z"));
            registry(c).write("dataset", "sales_ds", Map.of("view", "sales_view"));
            registry(c).write("query", "ds_q", Map.of(
                    "type", "sql", "datasetId", "sales_ds", "text", "SELECT label, amount FROM sales_ds"));

            JsonNode data = JSON.readTree(assertOk(run(c.port, "ds_q", null))).get("data");
            assertEquals(1, data.get("rows").size());
            assertEquals("x", data.get("rows").get(0).get("label").asText());
            assertRole(data, "amount", "measure");
        }
    }

    @Test
    void unknownQueryIs404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, run(c.port, "nope", null).statusCode());
        }
    }

    @Test
    void structuredQueryIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            registry(c).write("query", "struct_q", Map.of("type", "structured", "text", "x"));
            assertEquals(422, run(c.port, "struct_q", null).statusCode());
        }
    }

    @Test
    void fileReadingSqlIsRejectedByGuard(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            registry(c).write("query", "evil_q", Map.of(
                    "type", "sql", "text", "SELECT * FROM read_csv('/etc/passwd')"));
            HttpResponse<String> r = run(c.port, "evil_q", null);
            assertEquals(422, r.statusCode());
            JsonNode err = JSON.readTree(r.body()).get("error");
            assertEquals("CONFIG_VALIDATION_FAILED", err.get("errorCode").asText());
            assertTrue(err.get("details").get("findings").isArray(), "the SqlGuard findings ride error.details");
        }
    }

    private String assertOk(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    private static void assertRole(JsonNode data, String column, String role) {
        for (JsonNode col : data.get("resultSet").get("columns"))
            if (col.get("name").asText().equals(column)) {
                assertEquals(role, col.get("role").asText(), "role of " + column);
                return;
            }
        fail("column '" + column + "' not in result set");
    }
}
