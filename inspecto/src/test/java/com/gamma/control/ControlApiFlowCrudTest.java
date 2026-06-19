package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.SourceService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authored-flow topology CRUD (T19, §7.1) over real HTTP: create / list / get / update / delete + incremental
 * node/edge edits under {@code <write-root>/flows}, with {@code FlowValidator} gating (422) and the write-root
 * 503 gate. Distinct from the read-only lifted-pipeline projection ({@code /flows}, {@code /flows/{id}/graph}).
 */
class ControlApiFlowCrudTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** A valid 2-node flow: acquisition --data--> persistent sink. */
    private static final String VALID = """
        {"name":"demo_flow","active":false,
         "nodes":[{"id":"acq","type":"acquisition"},
                  {"id":"sink","type":"sink.persistent","config":{"store":"out"}}],
         "edges":[{"from":"acq","rel":"data","to":"sink"}]}""";

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    @Test
    void crudLifecycleAndIncrementalEdits(@TempDir Path dir) throws Exception {
        Path wr = dir.resolve("wr");
        try (Ctx c = open(dir, wr)) {
            // create
            HttpResponse<String> created = send(c.port, "POST", "/flows/authored", VALID);
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("demo_flow", json(created).get("name").asText());
            assertTrue(Files.exists(wr.resolve("flows/demo_flow.toon")));

            // duplicate → 409
            assertEquals(409, send(c.port, "POST", "/flows/authored", VALID).statusCode());

            // list + get
            assertEquals(1, json(send(c.port, "GET", "/flows/authored", null)).size());
            JsonNode g = json(send(c.port, "GET", "/flows/authored/demo_flow", null));
            assertEquals(2, g.get("nodes").size());

            // add a node (parser) then wire acq --data--> it
            assertEquals(200, send(c.port, "POST", "/flows/authored/demo_flow/nodes",
                    "{\"id\":\"p\",\"type\":\"parser\"}").statusCode());
            JsonNode afterNode = json(send(c.port, "GET", "/flows/authored/demo_flow", null));
            assertEquals(3, afterNode.get("nodes").size());
            assertEquals(200, send(c.port, "POST", "/flows/authored/demo_flow/edges",
                    "{\"from\":\"acq\",\"rel\":\"data\",\"to\":\"p\"}").statusCode());

            // an edge that breaks the node-output contract (sink.persistent does not emit data) → 422
            assertEquals(422, send(c.port, "POST", "/flows/authored/demo_flow/edges",
                    "{\"from\":\"sink\",\"rel\":\"data\",\"to\":\"acq\"}").statusCode());

            // replace via PUT (URL id authoritative)
            assertEquals(200, send(c.port, "PUT", "/flows/authored/demo_flow", VALID).statusCode());
            assertEquals(2, json(send(c.port, "GET", "/flows/authored/demo_flow", null)).get("nodes").size());

            // delete
            assertEquals(200, send(c.port, "DELETE", "/flows/authored/demo_flow", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/flows/authored/demo_flow", null).statusCode());
        }
    }

    @Test
    void rawReturnsLosslessNodeConfigForTheEditor(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String flow = """
                {"name":"cfg_flow","active":false,
                 "nodes":[{"id":"acq","type":"acquisition"},
                          {"id":"flt","type":"transform.filter","config":{"where":"amt >= 100"}},
                          {"id":"sink","type":"sink.persistent","config":{"store":"out"}}],
                 "edges":[{"from":"acq","rel":"data","to":"flt"},{"from":"flt","rel":"data","to":"sink"}]}""";
            assertEquals(200, send(c.port, "POST", "/flows/authored", flow).statusCode());

            // the structural projection omits raw node config…
            JsonNode projFlt = node(json(send(c.port, "GET", "/flows/authored/cfg_flow", null)).get("nodes"), "flt");
            assertTrue(projFlt.get("config") == null || projFlt.get("config").isNull(), "projection is structural-only");

            // …but /raw round-trips it losslessly, so the editor can edit + PUT without dropping config.
            JsonNode rawFlt = node(json(send(c.port, "GET", "/flows/authored/cfg_flow/raw", null)).get("nodes"), "flt");
            assertEquals("amt >= 100", rawFlt.get("config").get("where").asText());

            assertEquals(404, send(c.port, "GET", "/flows/authored/ghost/raw", null).statusCode());
        }
    }

    private static JsonNode node(JsonNode nodes, String id) {
        for (JsonNode n : nodes) if (id.equals(n.get("id").asText())) return n;
        throw new AssertionError("no node '" + id + "' in " + nodes);
    }

    @Test
    void dryRunReturnsPerNodeAndPerSinkCounts(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            String flow = """
                {"name":"dr_flow","active":false,
                 "nodes":[{"id":"acq","type":"acquisition"},
                          {"id":"flt","type":"transform.filter","config":{"where":"CAST(amt AS INT) >= 100"}},
                          {"id":"sink","type":"sink.persistent","config":{"store":"big"}}],
                 "edges":[{"from":"acq","rel":"data","to":"flt"},{"from":"flt","rel":"data","to":"sink"}]}""";
            assertEquals(200, send(c.port, "POST", "/flows/authored", flow).statusCode());

            HttpResponse<String> r = send(c.port, "POST", "/flows/authored/dr_flow/dry-run",
                    "{\"sampleRows\":[{\"id\":\"1\",\"amt\":\"150\"},{\"id\":\"2\",\"amt\":\"50\"},{\"id\":\"3\",\"amt\":\"200\"}]}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = json(r);
            assertEquals("acq", body.get("seedNode").asText());
            int sinkRows = body.get("sinks").get(0).get("rowCount").asInt();
            assertEquals(2, sinkRows, body.toString());   // amt 150, 200 pass the filter

            // dry-run of a missing flow → 404
            assertEquals(404, send(c.port, "POST", "/flows/authored/ghost/dry-run", "{\"sampleRows\":[{}]}").statusCode());
        }
    }

    @Test
    void invalidFlowIsRejected(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("wr"))) {
            // dangling edge → FlowValidator error → 422
            String dangling = "{\"name\":\"bad\",\"nodes\":[{\"id\":\"acq\",\"type\":\"acquisition\"}],"
                    + "\"edges\":[{\"from\":\"acq\",\"rel\":\"data\",\"to\":\"ghost\"}]}";
            assertEquals(422, send(c.port, "POST", "/flows/authored", dangling).statusCode());
            // malformed shape (no name) → 400
            assertEquals(400, send(c.port, "POST", "/flows/authored", "{\"active\":true}").statusCode());
        }
    }

    @Test
    void writesAreGatedOnTheWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(503, send(c.port, "POST", "/flows/authored", VALID).statusCode());
            assertEquals(List.of(), JSON.readValue(send(c.port, "GET", "/flows/authored", null).body(), List.class));
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
