package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.catalog.SemanticModel;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the v3.2.0 {@code /catalog*} routes over real HTTP (P6). */
class ControlApiCatalogTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private static SemanticModel semantics() {
        var kpi = new SemanticModel.KpiMeta("daily", "Daily count", "day",
                List.of("mini_etl/mini"), List.of("ID"));
        return new SemanticModel("sem", Map.of(), Map.of("daily", kpi), Map.of(),
                new SemanticModel.DomainNotes("USD", "UTC", List.of("excludes tax")));
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        SourceService svc = new SourceService(List.of(pipe), List.of(), List.of(),
                List.of(semantics()), 3600, 1, null);
        ControlApi api = new ControlApi(svc, 0, TOKEN);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return client.send(b.method("GET", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    @Test
    void catalogRoutesAreScopedAssistRead(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(401, get(c.port, "/catalog", null).statusCode(), "no token -> locked");
            assertEquals(401, get(c.port, "/catalog", "wrong").statusCode(), "bad token -> 401");
            assertEquals(200, get(c.port, "/catalog", TOKEN).statusCode(), "control token satisfies assist.read");
        }
    }

    @Test
    void catalogListsEmittedTables(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = JSON.readTree(get(c.port, "/catalog", TOKEN).body());
            assertTrue(body.isArray());
            boolean hasEvent = false;
            for (JsonNode n : body) if ("event:mini_etl/mini".equals(n.get("id").asText())) hasEvent = true;
            assertTrue(hasEvent, "the emitted event table is listed: " + body);
        }
    }

    @Test
    void graphTraversesFromKpiDownToSource(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port,
                    "/catalog/graph?from=kpi:daily&depth=5&direction=both", TOKEN);
            assertEquals(200, r.statusCode());
            JsonNode g = JSON.readTree(r.body());
            boolean reachesSource = false;
            for (JsonNode n : g.get("nodes")) if ("source:mini_etl".equals(n.get("id").asText())) reachesSource = true;
            assertTrue(reachesSource, "KPI traversal reaches the source: " + g.get("nodes"));
        }
    }

    @Test
    void tableDetailReturnsNodeAndNeighbours(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = get(c.port, "/catalog/tables/event:mini_etl/mini", TOKEN);
            assertEquals(200, r.statusCode());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("event:mini_etl/mini", body.get("node").get("id").asText());
            assertNotNull(body.get("node").get("overlay"), "detail hydrates the overlay");
            assertTrue(body.get("neighbors").get("nodes").size() > 1, "neighbours include schema/columns");
        }
    }

    @Test
    void kpisEndpointReturnsCatalogAndDomain(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode body = JSON.readTree(get(c.port, "/catalog/kpis", TOKEN).body());
            JsonNode kpis = body.get("kpis");
            assertEquals(1, kpis.size());
            assertEquals("daily", kpis.get(0).get("name").asText());
            // bare ref resolved to the event-table node id
            assertEquals("event:mini_etl/mini", kpis.get(0).get("inputs").get(0).asText());
            assertEquals("USD", body.get("domain").get("currency").asText());
        }
    }

    @Test
    void unknownNodeIs404AndBadFilterIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, get(c.port, "/catalog/tables/kpi:nope", TOKEN).statusCode());
            assertEquals(400, get(c.port, "/catalog/graph?direction=sideways", TOKEN).statusCode());
            assertEquals(400, get(c.port, "/catalog/graph?kinds=BOGUS", TOKEN).statusCode());
        }
    }
}
