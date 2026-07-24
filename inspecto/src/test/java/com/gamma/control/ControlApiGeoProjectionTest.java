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
 * Geo Map Phase 4 backend ({@code POST /geo/projection}, {@code POST /geo/routes}): the DuckDB-side fold of
 * {@code projectPoints}/{@code projectRoutes} — valid-coordinate projection with a skipped count, the O/D
 * route aggregation with summed weight, truncation, and the fail-closed gates. Also covers the ComponentStore
 * widening: a {@code geo-map-view} component persists via /components.
 */
class ControlApiGeoProjectionTest {

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

    /** A sightings dataset: three valid points (two share a kind), one out-of-range, one NULL coordinate. */
    private void seedPoints(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sights_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('alice', 23.81, 90.41, 'call'),"
                        + "('bob',   23.72, 90.40, 'call'),"
                        + "('carol', 40.71, -74.00, 'sms'),"
                        + "('dave',  999.0, 90.0,  'call'),"     // out of range → skipped
                        + "('erin',  NULL,  90.0,  'sms')"       // NULL lat → skipped
                        + ") AS t(who, lat, lon, kind)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "sights_ds", Map.of("view", "sights_view"));
    }

    private HttpResponse<String> post(int port, String route, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/" + route))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void projectsValidPointsAndCountsSkipped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedPoints(c);
            HttpResponse<String> r = post(c.port, "geo/projection", """
                    {"dataset":"sights_ds","latCol":"lat","lonCol":"lon","entityCol":"who","kindCol":"kind"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(3, data.get("points").size(), "two bad coordinates are excluded: " + data);
            assertEquals(2, data.get("skipped").asInt(), "out-of-range + NULL rows are skipped");
            assertFalse(data.get("truncated").asBoolean());
            JsonNode first = data.get("points").get(0);
            assertEquals("alice", first.get("label").asText());
            assertEquals("call", first.get("kind").asText());
            assertEquals(23.81, first.get("lat").asDouble(), 1e-9);
        }
    }

    @Test
    void kindDefaultsToPointWhenUnmapped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedPoints(c);
            HttpResponse<String> r = post(c.port, "geo/projection",
                    "{\"dataset\":\"sights_ds\",\"latCol\":\"lat\",\"lonCol\":\"lon\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode points = JSON.readTree(r.body()).at("/data/points");
            assertEquals(3, points.size());
            assertEquals("point", points.get(0).get("kind").asText(), "no kindCol → the 'point' default");
        }
    }

    @Test
    void limitTruncatesTheProjection(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedPoints(c);
            HttpResponse<String> r = post(c.port, "geo/projection", """
                    {"dataset":"sights_ds","latCol":"lat","lonCol":"lon","limit":2}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(2, data.get("points").size());
            assertTrue(data.get("truncated").asBoolean());
            assertEquals(2, data.get("skipped").asInt(), "skipped counts all bad rows, independent of the limit");
        }
    }

    @Test
    void attrColsFoldIntoPointAttrs(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedPoints(c);
            HttpResponse<String> r = post(c.port, "geo/projection", """
                    {"dataset":"sights_ds","latCol":"lat","lonCol":"lon","attrCols":["who"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode points = JSON.readTree(r.body()).at("/data/points");
            assertEquals("alice", points.get(0).get("attrs").get("who").asText());
        }
    }

    /** An O/D dataset: the alice→bob call appears twice (folds to weight 2), plus one bad endpoint. */
    private void seedRoutes(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("trips_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('alice', 23.8, 90.4, 'bob',   23.7, 90.4, 'call'),"
                        + "('alice', 23.8, 90.4, 'bob',   23.7, 90.4, 'call'),"
                        + "('alice', 23.8, 90.4, 'carol', 40.7, -74.0, 'sms'),"
                        + "('dave',  23.8, 90.4, 'erin',  999.0, 0.0,  'call')"   // bad dest → skipped
                        + ") AS t(a, alat, alon, b, blat, blon, kind)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "trips_ds", Map.of("view", "trips_view"));
    }

    @Test
    void routesFoldByEndpointAndKindWithSummedWeight(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedRoutes(c);
            HttpResponse<String> r = post(c.port, "geo/routes", """
                    {"dataset":"trips_ds","fromLatCol":"alat","fromLonCol":"alon","toLatCol":"blat","toLonCol":"blon",
                     "fromCol":"a","toCol":"b","kindCol":"kind"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(2, data.get("routes").size(), "two distinct O/D+kind routes survive: " + data);
            assertEquals(3, data.get("points").size(), "alice, bob, carol fold into 3 endpoints");
            assertEquals(1, data.get("skipped").asInt(), "the out-of-range destination row is skipped");
            JsonNode heaviest = data.get("routes").get(0);
            assertEquals("ep:alice", heaviest.get("from").asText());
            assertEquals("ep:bob", heaviest.get("to").asText());
            assertEquals("call", heaviest.get("kind").asText());
            assertEquals(2, heaviest.get("weight").asInt(), "the duplicated alice->bob call folds to weight 2");
        }
    }

    @Test
    void failsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedPoints(c);
            assertEquals(404, post(c.port, "geo/projection",
                    "{\"dataset\":\"ghost\",\"latCol\":\"lat\",\"lonCol\":\"lon\"}").statusCode());
            assertEquals(422, post(c.port, "geo/projection",
                    "{\"dataset\":\"sights_ds\",\"latCol\":\"la t\",\"lonCol\":\"lon\"}").statusCode(),
                    "non-identifier column");
            assertEquals(422, post(c.port, "geo/projection",
                    "{\"dataset\":\"sights_ds\",\"latCol\":\"lat\"}").statusCode(), "missing lonCol");
            assertEquals(422, post(c.port, "geo/routes",
                    "{\"dataset\":\"sights_ds\",\"fromLatCol\":\"lat\"}").statusCode(), "missing route columns");
        }
    }

    @Test
    void savedGeoViewsPersistViaComponents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String view = """
                    {"id":"dhaka-map","name":"Dhaka map","sourceId":"dataset",
                     "query":{"projection":{"datasetId":"sights_ds","latCol":"lat","lonCol":"lon"}}}""";
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1/components/geo-map-view"))
                    .method("POST", BodyPublishers.ofString(view)).build(), BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());

            assertTrue(new ComponentStore(c.root.resolve("registry")).get("geo-map-view", "dhaka-map").isPresent(),
                    "saved geo view lands in the real component store");
        }
    }
}
