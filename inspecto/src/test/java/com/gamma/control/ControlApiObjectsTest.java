package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
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
 * Phase-2 Alert Center routes over real HTTP: {@code /objects} query + by-id, the ack/resolve lifecycle,
 * the generic {@code /objects/{id}/transition}, and the error gates (404 unknown, 422 illegal move,
 * 400 bad type/empty body, 401 unauthenticated). Objects are seeded through the service API, then
 * exercised over the wire.
 */
class ControlApiObjectsTest {

    private static final String TOKEN = "secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0, TOKEN);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void queryAckResolveLifecycleAndErrorGates(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = c.svc.objects().open(ObjectType.ALERT, "disk full", "msg",
                    "CRITICAL", "pipeA", Map.of("rule", "r1"));

            // list + filter
            JsonNode list = json(send(c.port, "GET", "/objects?type=ALERT&status=OPEN", TOKEN, null));
            assertTrue(list.isArray() && list.size() == 1);
            assertEquals(seed.id(), list.get(0).get("id").asText());
            assertEquals("CRITICAL", list.get(0).get("severity").asText());

            // by id: hit + miss
            assertEquals(200, send(c.port, "GET", "/objects/" + seed.id(), TOKEN, null).statusCode());
            assertEquals(404, send(c.port, "GET", "/objects/none", TOKEN, null).statusCode());

            // ack → ACKNOWLEDGED
            JsonNode acked = json(send(c.port, "POST", "/objects/" + seed.id() + "/ack", TOKEN,
                    "{\"actor\":\"alice\"}"));
            assertEquals("ACKNOWLEDGED", acked.get("status").asText());

            // resolve → RESOLVED (terminal: closedAt set)
            JsonNode resolved = json(send(c.port, "POST", "/objects/" + seed.id() + "/resolve", TOKEN, null));
            assertEquals("RESOLVED", resolved.get("status").asText());
            assertTrue(resolved.get("closedAt").asLong() > 0);

            // illegal transition (ack a resolved alert) → 422
            assertEquals(422, send(c.port, "POST", "/objects/" + seed.id() + "/ack", TOKEN, null).statusCode());
            // unknown id transition → 404
            assertEquals(404, send(c.port, "POST", "/objects/none/ack", TOKEN, null).statusCode());
            // bad type filter → 400
            assertEquals(400, send(c.port, "GET", "/objects?type=bogus", TOKEN, null).statusCode());
            // auth enforced
            assertEquals(401, send(c.port, "GET", "/objects", null, null).statusCode());
        }
    }

    @Test
    void genericTransitionByTargetStatus(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = c.svc.objects().open(ObjectType.ALERT, "t", "d", "INFO", "pipeB", Map.of());
            JsonNode out = json(send(c.port, "POST", "/objects/" + seed.id() + "/transition", TOKEN,
                    "{\"status\":\"ACKNOWLEDGED\",\"actor\":\"bob\"}"));
            assertEquals("ACKNOWLEDGED", out.get("status").asText());
            // neither action nor status → 400
            assertEquals(400, send(c.port, "POST", "/objects/" + seed.id() + "/transition", TOKEN, "{}").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
