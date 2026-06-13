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

    @Test
    void createIssueAndWalkLifecycle(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // create an ISSUE via POST /objects (type defaults to ISSUE); dueInMinutes seeds the SLA deadline
            JsonNode created = json(send(c.port, "POST", "/objects", TOKEN,
                    "{\"title\":\"bad rows\",\"severity\":\"HIGH\",\"assignee\":\"alice\",\"dueInMinutes\":30}"));
            String id = created.get("id").asText();
            assertEquals("ISSUE", created.get("objectType").asText());
            assertEquals("OPEN", created.get("status").asText());
            assertEquals("alice", created.get("assignee").asText());
            assertTrue(created.get("attributes").has("dueAt"), "dueInMinutes sets a dueAt attribute");

            // walk OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED via the generic transition route
            assertEquals("ASSIGNED", transition(c.port, id, "assign"));
            assertEquals("IN_PROGRESS", transition(c.port, id, "start"));
            assertEquals("RESOLVED", transition(c.port, id, "resolve"));
            JsonNode closed = json(send(c.port, "POST", "/objects/" + id + "/transition", TOKEN,
                    "{\"action\":\"close\",\"actor\":\"bob\"}"));
            assertEquals("CLOSED", closed.get("status").asText());
            assertTrue(closed.get("closedAt").asLong() > 0, "CLOSED is terminal → closedAt set");

            // it lists under a type filter; an illegal next move → 422
            JsonNode issues = json(send(c.port, "GET", "/objects?type=ISSUE", TOKEN, null));
            assertTrue(issues.isArray() && issues.size() == 1 && id.equals(issues.get(0).get("id").asText()));
            assertEquals(422, send(c.port, "POST", "/objects/" + id + "/transition", TOKEN,
                    "{\"action\":\"start\"}").statusCode());

            // missing title → 400; unknown type → 400
            assertEquals(400, send(c.port, "POST", "/objects", TOKEN, "{\"severity\":\"LOW\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/objects", TOKEN, "{\"title\":\"x\",\"type\":\"bogus\"}").statusCode());
        }
    }

    /** POST a generic {action} transition and return the resulting status. */
    private String transition(int port, String id, String action) throws Exception {
        return json(send(port, "POST", "/objects/" + id + "/transition", TOKEN,
                "{\"action\":\"" + action + "\"}")).get("status").asText();
    }

    @Test
    void commentsAttachmentsAndRca(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject caseObj = c.svc.objects().open(ObjectType.CASE, "investigation", "d",
                    "HIGH", "corr", Map.of());

            // comment
            JsonNode comment = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/comments", TOKEN,
                    "{\"body\":\"starting\",\"author\":\"alice\"}"));
            assertEquals("COMMENT", comment.get("kind").asText());
            assertEquals("starting", comment.get("body").asText());

            // attachment (evidence reference — metadata only)
            JsonNode att = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/attachments", TOKEN,
                    "{\"name\":\"trace.log\",\"uri\":\"s3://x/trace.log\",\"contentType\":\"text/plain\",\"author\":\"bob\"}"));
            assertEquals("ATTACHMENT", att.get("kind").asText());
            assertEquals("trace.log", att.get("attributes").get("name").asText());

            // RCA seeds one comment per section (inline template)
            JsonNode rca = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/rca", TOKEN,
                    "{\"sections\":[\"Summary\",\"Root cause\",\"Remediation\"],\"actor\":\"alice\"}"));
            assertTrue(rca.isArray() && rca.size() == 3);

            // lists: 1 attachment; 1 manual + 3 RCA = 4 comments
            assertEquals(1, json(send(c.port, "GET", "/objects/" + caseObj.id() + "/attachments", TOKEN, null)).size());
            assertEquals(4, json(send(c.port, "GET", "/objects/" + caseObj.id() + "/comments", TOKEN, null)).size());

            // gates: missing body → 400; attachment missing uri → 400; unknown object → 404
            assertEquals(400, send(c.port, "POST", "/objects/" + caseObj.id() + "/comments", TOKEN, "{}").statusCode());
            assertEquals(400, send(c.port, "POST", "/objects/" + caseObj.id() + "/attachments", TOKEN,
                    "{\"name\":\"x\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/comments", TOKEN,
                    "{\"body\":\"x\"}").statusCode());
        }
    }

    @Test
    void linkObjectsAndTraverseGraph(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject caseObj = c.svc.objects().open(ObjectType.CASE, "investigation", "d",
                    "HIGH", "corr", Map.of());
            OperationalObject issue = c.svc.objects().open(ObjectType.ISSUE, "bad rows", "d",
                    "HIGH", "corr", Map.of());

            // CASE CONTAINS ISSUE
            JsonNode link = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/links", TOKEN,
                    "{\"to\":\"" + issue.id() + "\",\"relationship\":\"contains\",\"actor\":\"alice\"}"));
            assertEquals("CONTAINS", link.get("relationship").asText());
            assertEquals(caseObj.id(), link.get("from").asText());
            assertEquals("ISSUE", link.get("toType").asText());

            // links incident to the case
            JsonNode links = json(send(c.port, "GET", "/objects/" + caseObj.id() + "/links", TOKEN, null));
            assertTrue(links.isArray() && links.size() == 1);

            // correlation subgraph around the case
            JsonNode graph = json(send(c.port, "GET", "/objects/" + caseObj.id() + "/graph?depth=2", TOKEN, null));
            assertEquals(2, graph.get("nodes").size());
            assertEquals(1, graph.get("edges").size());

            // missing 'to' → 400; unknown endpoint → 404; unknown graph root → 404
            assertEquals(400, send(c.port, "POST", "/objects/" + caseObj.id() + "/links", TOKEN, "{}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/" + caseObj.id() + "/links", TOKEN,
                    "{\"to\":\"nope\"}").statusCode());
            assertEquals(404, send(c.port, "GET", "/objects/nope/graph", TOKEN, null).statusCode());
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
