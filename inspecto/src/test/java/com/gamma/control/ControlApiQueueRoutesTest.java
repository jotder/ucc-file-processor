package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
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
 * INC-4 over real HTTP: {@code /queues} create + list + by-id, {@code /objects/{id}/assign} (explicit and
 * queue-routed), the watcher endpoints, and the error gates (400 missing fields, 404 unknown, 422 unroutable
 * manual queue). Objects are seeded through the service API, then exercised over the wire.
 */
class ControlApiQueueRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void createQueueThenAssignByQueueRoutesRoundRobin(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // create a queue over HTTP
            JsonNode q = json(send(c.port, "POST", "/queues",
                    "{\"id\":\"triage\",\"name\":\"Triage\",\"members\":[\"alice\",\"bob\"],\"routing\":\"round_robin\"}"));
            assertEquals("triage", q.get("id").asText());
            assertEquals(2, q.get("members").size());
            assertEquals("round_robin", q.get("routing").asText());

            // it lists + resolves by id
            assertEquals(1, json(send(c.port, "GET", "/queues", null)).size());
            assertEquals(200, send(c.port, "GET", "/queues/triage", null).statusCode());
            assertEquals(404, send(c.port, "GET", "/queues/none", null).statusCode());

            OperationalObject i1 = c.svc.objects().open(ObjectType.INCIDENT, "one", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject i2 = c.svc.objects().open(ObjectType.INCIDENT, "two", "d", "HIGH", null, null, null, "corr", Map.of());

            JsonNode a1 = json(send(c.port, "POST", "/objects/" + i1.id() + "/assign", "{\"queue\":\"triage\"}"));
            assertEquals("alice", a1.get("assignee").asText());
            assertEquals("IDENTIFIED", a1.get("status").asText(),
                    "the mail lifecycle has no 'assign' action — assignment doesn't move status");
            assertEquals("bob", json(send(c.port, "POST", "/objects/" + i2.id() + "/assign",
                    "{\"queue\":\"triage\"}")).get("assignee").asText(), "round-robin advances");
        }
    }

    @Test
    void assignExplicitAndGates(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.svc.objects().registerQueue(com.gamma.ops.queue.Queue.fromMap(Map.of(
                    "id", "manual", "members", List.of("alice"), "routing", "manual")));
            OperationalObject i = c.svc.objects().open(ObjectType.INCIDENT, "x", "d", "HIGH", null, null, null, "corr", Map.of());

            // explicit assignee wins
            assertEquals("dana", json(send(c.port, "POST", "/objects/" + i.id() + "/assign",
                    "{\"assignee\":\"dana\"}")).get("assignee").asText());

            // gates: neither field → 400; unknown object → 404; unknown queue → 404; manual w/o assignee → 422
            assertEquals(400, send(c.port, "POST", "/objects/" + i.id() + "/assign", "{}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/assign", "{\"assignee\":\"x\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/" + i.id() + "/assign", "{\"queue\":\"ghost\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/objects/" + i.id() + "/assign", "{\"queue\":\"manual\"}").statusCode());

            // bad routing value on create → 400
            assertEquals(400, send(c.port, "POST", "/queues", "{\"id\":\"q\",\"routing\":\"bogus\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/queues", "{\"name\":\"no id\"}").statusCode());
        }
    }

    @Test
    void watchUnwatchAndList(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject i = c.svc.objects().open(ObjectType.INCIDENT, "x", "d", "HIGH", null, null, null, "corr", Map.of());

            JsonNode watched = json(send(c.port, "POST", "/objects/" + i.id() + "/watch", "{\"user\":\"alice\"}"));
            assertEquals(List.of("alice"), asList(watched.get("watchers")));
            send(c.port, "POST", "/objects/" + i.id() + "/watch", "{\"user\":\"bob\"}");
            assertEquals(List.of("alice", "bob"), asList(json(send(c.port, "GET", "/objects/" + i.id() + "/watchers", null))));

            JsonNode unwatched = json(send(c.port, "POST", "/objects/" + i.id() + "/unwatch", "{\"user\":\"alice\"}"));
            assertEquals(List.of("bob"), asList(unwatched.get("watchers")));

            // gates: missing user → 400; unknown object → 404
            assertEquals(400, send(c.port, "POST", "/objects/" + i.id() + "/watch", "{}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/watch", "{\"user\":\"x\"}").statusCode());
            assertEquals(404, send(c.port, "GET", "/objects/nope/watchers", null).statusCode());
        }
    }

    private static List<String> asList(JsonNode arr) {
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
