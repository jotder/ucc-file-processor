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
 * INV-1 Entity Projection over a real Dataset ({@code POST /inv/projection}): distinct (source, target,
 * kind) triples with folded counts, typed link kinds, NULL-endpoint exclusion, and the fail-closed gates.
 * Also covers the ComponentStore widening: a {@code link-analysis-view} component persists via /components.
 */
class ControlApiInvProjectionTest {

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
