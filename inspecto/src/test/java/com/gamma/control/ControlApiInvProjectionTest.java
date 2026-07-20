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
 * INV-1 Entity Projection over a real Dataset ({@code POST /inv/projection}): distinct (source, target,
 * kind) triples with folded counts, typed link kinds, NULL-endpoint exclusion, and the fail-closed gates.
 * Also covers the ComponentStore widening: a {@code link-analysis-view} component persists via /components.
 */
class ControlApiInvProjectionTest {

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

    /** A call-record dataset: caller→callee links, one duplicated pair, one NULL endpoint. */
    private void seedCalls(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('alice','bob','sms'),"
                        + "('alice','bob','sms'),"
                        + "('alice','carol','call'),"
                        + "('dave',NULL,'call')"
                        + ") AS t(caller,callee,channel)",
                "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
    }

    private HttpResponse<String> project(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/projection"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> neighbors(int port, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/projection/neighbors"))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> schemaRelationships(int port) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/inv/schema/relationships"))
                .GET().build(), BodyHandlers.ofString());
    }

    @Test
    void projectsTypedFoldedTriples(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","linkKindCol":"channel"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            JsonNode rows = data.get("rows");
            assertEquals(2, rows.size(), "duplicates fold, the NULL-endpoint row is excluded: " + rows);
            // Heaviest first: alice→bob (sms) folded to count 2.
            assertEquals("alice", rows.get(0).get("source").asText());
            assertEquals("bob", rows.get(0).get("target").asText());
            assertEquals("sms", rows.get(0).get("kind").asText());
            assertEquals(2, rows.get(0).get("count").asInt());
            assertEquals(1, rows.get(1).get("count").asInt());
            assertFalse(data.get("truncated").asBoolean());
        }
    }

    @Test
    void untypedProjectionFoldsAcrossKinds(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).at("/data/rows");
            assertEquals(2, rows.size());
            assertTrue(rows.get(0).get("kind").isNull(), "no linkKindCol → kind is null");
        }
    }

    @Test
    void limitTruncatesHeaviestFirst(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","limit":1}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(1, data.get("rows").size());
            assertEquals(2, data.get("rows").get(0).get("count").asInt(), "the folded pair survives the cut");
            assertTrue(data.get("truncated").asBoolean());
        }
    }

    @Test
    void attrColsJoinTheFoldKeyAndRoundTripInOutput(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","attrCols":["channel"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).at("/data/rows");
            assertEquals(2, rows.size(), "channel is uniform per pair here, so the fold is unchanged: " + rows);
            JsonNode aliceBob = rows.get(0);
            assertEquals("alice", aliceBob.get("source").asText());
            assertEquals(2, aliceBob.get("count").asInt());
            assertEquals("sms", aliceBob.get("attrs").get("channel").asText());
        }
    }

    @Test
    void attrColsSplitAFoldedPairWhenTheAttributeValueDiffers(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("mixed_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES "
                            + "('alice','bob','sms'),"
                            + "('alice','bob','call')"
                            + ") AS t(caller,callee,channel)",
                    "2026-07-08T00:00:00Z"));
            new ComponentStore(c.root.resolve("registry")).write("dataset", "mixed_ds", Map.of("view", "mixed_view"));

            HttpResponse<String> r = project(c.port, """
                    {"dataset":"mixed_ds","sourceCol":"caller","targetCol":"callee","attrCols":["channel"]}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).at("/data/rows");
            assertEquals(2, rows.size(), "differing attr values fold into separate rows, not one merged row: " + rows);
            for (JsonNode row : rows) assertEquals(1, row.get("count").asInt());
        }
    }

    @Test
    void neighborsReturnsOnlyRowsTouchingTheGivenValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            HttpResponse<String> r = neighbors(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","value":"bob"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).at("/data/rows");
            assertEquals(1, rows.size(), "only alice->bob touches 'bob': " + rows);
            assertEquals("alice", rows.get(0).get("source").asText());
            assertEquals("bob", rows.get(0).get("target").asText());
            assertEquals(2, rows.get(0).get("count").asInt());
        }
    }

    @Test
    void neighborsMatchesEitherEndpointAndEscapesQuotes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("names_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('a''b','x'),('y','a''b')) AS t(caller,callee)",
                    "2026-07-08T00:00:00Z"));
            new ComponentStore(c.root.resolve("registry")).write("dataset", "names_ds", Map.of("view", "names_view"));

            HttpResponse<String> r = neighbors(c.port, """
                    {"dataset":"names_ds","sourceCol":"caller","targetCol":"callee","value":"a'b"}""");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode rows = JSON.readTree(r.body()).at("/data/rows");
            assertEquals(2, rows.size(), "matches as both source and target: " + rows);
        }
    }

    @Test
    void neighborsRequiresValue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            assertEquals(422, neighbors(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}").statusCode());
        }
    }

    @Test
    void failsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedCalls(c);
            assertEquals(404, project(c.port,
                    "{\"dataset\":\"ghost\",\"sourceCol\":\"a\",\"targetCol\":\"b\"}").statusCode());
            assertEquals(422, project(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"a b\",\"targetCol\":\"callee\"}").statusCode(),
                    "non-identifier column");
            assertEquals(422, project(c.port,
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\"}").statusCode(), "missing targetCol");
            assertEquals(422, project(c.port, """
                    {"dataset":"calls_ds","sourceCol":"caller","targetCol":"callee","attrCols":["a b"]}""")
                    .statusCode(), "non-identifier attrCols entry");
        }
    }

    /** Two datasets whose naming convention should be inferrable: orders.customer_id -> customers.id. */
    private void seedOrdersAndCustomers(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("customers_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (1,'Alice'),(2,'Bob')) AS t(id,name)", "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "customers", Map.of("view", "customers_view"));
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("orders_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES (100,1),(101,2)) AS t(order_id,customer_id)", "2026-07-08T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "orders", Map.of("view", "orders_view"));
    }

    @Test
    void schemaRelationshipsInfersFkToIdColumnByNamingConvention(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrdersAndCustomers(c);
            HttpResponse<String> r = schemaRelationships(c.port);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(2, data.get("datasetsScanned").asInt());
            JsonNode rels = data.get("relationships");
            boolean found = false;
            for (JsonNode rel : rels) {
                if (rel.get("fromDataset").asText().equals("orders")
                        && rel.get("fromColumn").asText().equals("customer_id")) {
                    assertEquals("customers", rel.get("toDataset").asText());
                    assertEquals("id", rel.get("toColumn").asText());
                    assertEquals("high", rel.get("confidence").asText());
                    found = true;
                }
            }
            assertTrue(found, "expected orders.customer_id -> customers.id: " + rels);
        }
    }

    @Test
    void schemaRelationshipsSkipsUnusableDatasetsWithoutFailing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedOrdersAndCustomers(c);
            new ComponentStore(c.root.resolve("registry")).write("dataset", "ghost_ds", Map.of());   // unbound
            HttpResponse<String> r = schemaRelationships(c.port);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(2, data.get("datasetsScanned").asInt());
            assertEquals(1, data.get("datasetsSkipped").asInt());
        }
    }

    @Test
    void savedLinkAnalysisViewsPersistViaComponents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String view = """
                    {"id":"fraud-ring","name":"Fraud ring","sourceId":"entity-projection",
                     "query":{"projection":{"datasetId":"calls_ds","sourceCol":"caller","targetCol":"callee"}}}""";
            HttpResponse<String> created = client.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/api/v1/components/link-analysis-view"))
                    .method("POST", BodyPublishers.ofString(view)).build(), BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());

            ComponentStore store = new ComponentStore(c.root.resolve("registry"));
            assertTrue(store.get("link-analysis-view", "fraud-ring").isPresent(),
                    "saved view lands in the real component store (INV-1 mock-store retirement)");
        }
    }
}
