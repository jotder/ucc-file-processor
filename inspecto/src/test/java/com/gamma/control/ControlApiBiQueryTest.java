package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.SourceService;
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
 * Real-HTTP + real-DuckDB tests for the headless BI API (BI-7): {@code POST /bi/query} aggregates a
 * Dataset server-side from a measure spec (no authored query component), {@code GET /bi/datasets}
 * lists the queryable datasets, and the surface fails closed (404 unknown dataset, 422 bad spec).
 * Mirrors {@link ControlApiQueryRunV1Test}'s setup.
 */
class ControlApiBiQueryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            SourceService svc = new SourceService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    /** A view-backed dataset with three sales rows across two regions. */
    private void seedSales(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sales_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('EU',10.0),('EU',30.0),('US',5.0)) AS t(region,amount)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "sales_ds", Map.of("view", "sales_view"));
    }

    private HttpResponse<String> biQuery(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bi/query"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void aggregatesADatasetFromAMeasureSpec(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c);
            HttpResponse<String> r = biQuery(c.port, """
                    {"dataset":"sales_ds",
                     "measures":[{"agg":"sum","field":"amount"},{"agg":"count"}],
                     "groupBy":["region"],
                     "orderBy":[{"field":"sum_amount","dir":"desc"}]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(2, data.get("rows").size());
            JsonNode top = data.get("rows").get(0);
            assertEquals("EU", top.get("region").asText());
            assertEquals(40.0, top.get("sum_amount").asDouble(), 1e-9, "EU sums 10+30");
            assertEquals(2, top.get("count").asInt());
            assertTrue(data.get("sql").asText().startsWith("SELECT"), "compiled SQL echoed for transparency");
        }
    }

    @Test
    void filtersNarrowTheAggregation(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c);
            HttpResponse<String> r = biQuery(c.port, """
                    {"dataset":"sales_ds",
                     "measures":[{"agg":"sum","field":"amount"}],
                     "filters":[{"field":"region","op":"=","value":"US"}]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).get("data").get("rows");
            assertEquals(1, rows.size());
            assertEquals(5.0, rows.get(0).get("sum_amount").asDouble(), 1e-9);
        }
    }

    @Test
    void listsDatasets(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c);
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1/bi/datasets")).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(1, data.size());
            assertEquals("sales_ds", data.get(0).get("id").asText());
            assertEquals("view", data.get(0).get("binding").asText());
        }
    }

    @Test
    void failsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedSales(c);
            assertEquals(404, biQuery(c.port,
                    "{\"dataset\":\"ghost\",\"measures\":[{\"agg\":\"count\"}]}").statusCode());
            assertEquals(422, biQuery(c.port,
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"median\",\"field\":\"amount\"}]}").statusCode(),
                    "unknown aggregation");
            assertEquals(422, biQuery(c.port,
                    "{\"dataset\":\"sales_ds\"}").statusCode(), "empty spec");
            assertEquals(422, biQuery(c.port,
                    "{\"dataset\":\"sales_ds\",\"measures\":[{\"agg\":\"count\"}],\"groupBy\":[\"a; DROP\"]}").statusCode(),
                    "unsafe identifier");
        }
    }
}
