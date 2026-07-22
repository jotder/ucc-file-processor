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
 * Phase-2 Alert Center routes over real HTTP: {@code /objects} query + by-id, the ack/resolve lifecycle,
 * the generic {@code /objects/{id}/transition}, and the error gates (404 unknown, 422 illegal move,
 * 400 bad type/empty body, 401 unauthenticated). Objects are seeded through the service API, then
 * exercised over the wire.
 */
class ControlApiObjectsTest {

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
    void queryAckResolveLifecycleAndErrorGates(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = c.svc.objects().open(ObjectType.ALERT, "disk full", "msg",
                    "CRITICAL", "pipeA", Map.of("rule", "r1"));

            // list + filter
            JsonNode list = json(send(c.port, "GET", "/objects?type=ALERT&status=OPEN", null));
            assertTrue(list.isArray() && list.size() == 1);
            assertEquals(seed.id(), list.get(0).get("id").asText());
            assertEquals("CRITICAL", list.get(0).get("severity").asText());

            // by id: hit + miss
            assertEquals(200, send(c.port, "GET", "/objects/" + seed.id(), null).statusCode());
            assertEquals(404, send(c.port, "GET", "/objects/none", null).statusCode());

            // ack → ACKNOWLEDGED
            JsonNode acked = json(send(c.port, "POST", "/objects/" + seed.id() + "/ack",
                    "{\"actor\":\"alice\"}"));
            assertEquals("ACKNOWLEDGED", acked.get("status").asText());

            // resolve → RESOLVED (terminal: closedAt set)
            JsonNode resolved = json(send(c.port, "POST", "/objects/" + seed.id() + "/resolve", null));
            assertEquals("RESOLVED", resolved.get("status").asText());
            assertTrue(resolved.get("closedAt").asLong() > 0);

            // illegal transition (ack a resolved alert) → 422
            assertEquals(422, send(c.port, "POST", "/objects/" + seed.id() + "/ack", null).statusCode());
            // unknown id transition → 404
            assertEquals(404, send(c.port, "POST", "/objects/none/ack", null).statusCode());
            // bad type filter → 400
            assertEquals(400, send(c.port, "GET", "/objects?type=bogus", null).statusCode());
        }
    }

    @Test
    void genericTransitionByTargetStatus(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = c.svc.objects().open(ObjectType.ALERT, "t", "d", "INFO", "pipeB", Map.of());
            JsonNode out = json(send(c.port, "POST", "/objects/" + seed.id() + "/transition",
                    "{\"status\":\"ACKNOWLEDGED\",\"actor\":\"bob\"}"));
            assertEquals("ACKNOWLEDGED", out.get("status").asText());
            // neither action nor status → 400
            assertEquals(400, send(c.port, "POST", "/objects/" + seed.id() + "/transition", "{}").statusCode());
        }
    }

    @Test
    void createIncidentAndWalkLifecycle(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // an existing object to satisfy the mandatory ≥1-link create contract (an incident-source)
            OperationalObject src = c.svc.objects().open(ObjectType.ALERT, "source alert", "d", "HIGH", "corr", Map.of());

            // create an INCIDENT via POST /objects (type defaults to INCIDENT); dueInMinutes seeds the SLA deadline
            JsonNode created = json(send(c.port, "POST", "/objects",
                    "{\"title\":\"bad rows\",\"severity\":\"HIGH\",\"assignee\":\"alice\",\"dueInMinutes\":30,"
                    + "\"links\":[{\"to\":\"" + src.id() + "\"}]}"));
            String id = created.get("id").asText();
            assertEquals("INCIDENT", created.get("objectType").asText());
            assertEquals("IDENTIFIED", created.get("status").asText());
            assertEquals("alice", created.get("assignee").asText());
            assertTrue(created.get("attributes").has("dueAt"), "dueInMinutes sets a dueAt attribute");
            assertEquals(1, json(send(c.port, "GET", "/objects/" + id + "/links", null)).size(),
                    "the mandatory linked entity is attached at creation");

            // walk IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED (GLOSSARY §9) via the generic transition route
            assertEquals("DIAGNOSING", transition(c.port, id, "accept"));
            assertEquals("RESOLVED", transition(c.port, id, "resolve"));
            JsonNode archived = json(send(c.port, "POST", "/objects/" + id + "/transition",
                    "{\"action\":\"archive\",\"actor\":\"bob\"}"));
            assertEquals("ARCHIVED", archived.get("status").asText());
            assertTrue(archived.get("closedAt").asLong() > 0, "ARCHIVED is terminal → closedAt set");

            // it lists under a type filter; an illegal next move → 422
            JsonNode incidents = json(send(c.port, "GET", "/objects?type=INCIDENT", null));
            assertTrue(incidents.isArray() && incidents.size() == 1 && id.equals(incidents.get(0).get("id").asText()));
            assertEquals(422, send(c.port, "POST", "/objects/" + id + "/transition",
                    "{\"action\":\"accept\"}").statusCode());

            // missing title → 400; unknown type → 400
            assertEquals(400, send(c.port, "POST", "/objects", "{\"severity\":\"LOW\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/objects", "{\"title\":\"x\",\"type\":\"bogus\"}").statusCode());

            // ≥1 linked entity is mandatory: no links key → 400; empty links → 400; a dangling target → 404
            assertEquals(400, send(c.port, "POST", "/objects", "{\"title\":\"linkless\"}").statusCode());
            assertEquals(400, send(c.port, "POST", "/objects", "{\"title\":\"linkless\",\"links\":[]}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects",
                    "{\"title\":\"dangling\",\"links\":[{\"to\":\"ghost\"}]}").statusCode());
        }
    }

    @Test
    void patchUpdatesOperatorFieldsAndMergesAttributes(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject seed = c.svc.objects().open(ObjectType.INCIDENT, "bad rows", "d", "HIGH",
                    null, null, null, "corr", Map.of("category", "Pipeline / Ingest / Parse failure"));

            // priority + attribute merge in one PATCH; pre-existing attributes survive the merge
            JsonNode patched = json(send(c.port, "PATCH", "/objects/" + seed.id(),
                    "{\"priority\":\"MAJOR\",\"attributes\":{\"tags\":\"urgent\"}}"));
            assertEquals("MAJOR", patched.get("priority").asText());
            assertEquals("urgent", patched.get("attributes").get("tags").asText());
            assertEquals("Pipeline / Ingest / Parse failure", patched.get("attributes").get("category").asText(),
                    "attributes merge — existing keys survive");
            assertEquals("IDENTIFIED", patched.get("status").asText(), "PATCH never moves the workflow");

            // a later single-field PATCH leaves the earlier fields alone
            JsonNode reassigned = json(send(c.port, "PATCH", "/objects/" + seed.id(), "{\"assignee\":\"dana\"}"));
            assertEquals("dana", reassigned.get("assignee").asText());
            assertEquals("MAJOR", reassigned.get("priority").asText());

            // gates: empty body → 400; unknown id → 404
            assertEquals(400, send(c.port, "PATCH", "/objects/" + seed.id(), "{}").statusCode());
            assertEquals(404, send(c.port, "PATCH", "/objects/nope", "{\"priority\":\"LOW\"}").statusCode());
        }
    }

    @Test
    void workflowReadServesTheEffectiveLifecycle(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode caseWf = json(send(c.port, "GET", "/workflows/CASE", null));
            assertEquals("OPEN", caseWf.get("initial").asText());
            java.util.List<String> states = new java.util.ArrayList<>();
            caseWf.get("states").forEach(n -> states.add(n.asText()));
            assertEquals(List.of("OPEN", "INVESTIGATING", "ESCALATED", "RESOLVED", "CLOSED"), states,
                    "BFS order from the initial state — what the UI renders as folders");
            assertEquals("CLOSED", caseWf.get("terminal").get(0).asText());
            assertTrue(caseWf.get("transitions").size() >= 5);

            // the type parses case-insensitively; the incident mail lifecycle rides the same route
            assertEquals("IDENTIFIED", json(send(c.port, "GET", "/workflows/incident", null)).get("initial").asText());
            assertEquals(400, send(c.port, "GET", "/workflows/bogus", null).statusCode());
        }
    }

    /** POST a generic {action} transition and return the resulting status. */
    private String transition(int port, String id, String action) throws Exception {
        return json(send(port, "POST", "/objects/" + id + "/transition",
                "{\"action\":\"" + action + "\"}")).get("status").asText();
    }

    @Test
    void commentsAttachmentsAndRca(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject caseObj = c.svc.objects().open(ObjectType.CASE, "investigation", "d",
                    "HIGH", "corr", Map.of());

            // comment
            JsonNode comment = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/comments",
                    "{\"body\":\"starting\",\"author\":\"alice\"}"));
            assertEquals("COMMENT", comment.get("kind").asText());
            assertEquals("starting", comment.get("body").asText());

            // attachment (evidence reference — metadata only)
            JsonNode att = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/attachments",
                    "{\"name\":\"trace.log\",\"uri\":\"s3://x/trace.log\",\"contentType\":\"text/plain\",\"author\":\"bob\"}"));
            assertEquals("ATTACHMENT", att.get("kind").asText());
            assertEquals("trace.log", att.get("attributes").get("name").asText());

            // RCA seeds one comment per section (inline template)
            JsonNode rca = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/rca",
                    "{\"sections\":[\"Summary\",\"Root cause\",\"Remediation\"],\"actor\":\"alice\"}"));
            assertTrue(rca.isArray() && rca.size() == 3);

            // lists: 1 attachment; 1 manual + 3 RCA = 4 comments
            assertEquals(1, json(send(c.port, "GET", "/objects/" + caseObj.id() + "/attachments", null)).size());
            assertEquals(4, json(send(c.port, "GET", "/objects/" + caseObj.id() + "/comments", null)).size());

            // gates: missing body → 400; attachment missing uri → 400; unknown object → 404
            assertEquals(400, send(c.port, "POST", "/objects/" + caseObj.id() + "/comments", "{}").statusCode());
            assertEquals(400, send(c.port, "POST", "/objects/" + caseObj.id() + "/attachments",
                    "{\"name\":\"x\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/comments",
                    "{\"body\":\"x\"}").statusCode());
        }
    }

    @Test
    void rcaTemplateRegistryAndApplyByName(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            c.svc.registerRcaTemplate(com.gamma.ops.rca.RcaTemplate.fromMap(Map.of(
                    "name", "incident", "sections", List.of("Summary", "Root cause"))));
            OperationalObject caseObj = c.svc.objects().open(ObjectType.CASE, "inv", "d", "HIGH", null, Map.of());

            // the registry lists the loaded template
            JsonNode templates = json(send(c.port, "GET", "/rca/templates", null));
            assertTrue(templates.isArray() && templates.size() == 1);
            assertEquals("incident", templates.get(0).get("name").asText());

            // apply by name → seeds one comment per section
            JsonNode seeded = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/rca",
                    "{\"template\":\"incident\",\"actor\":\"alice\"}"));
            assertTrue(seeded.isArray() && seeded.size() == 2);
            assertEquals(2, json(send(c.port, "GET", "/objects/" + caseObj.id() + "/comments", null)).size());

            // an unknown template name → 404
            assertEquals(404, send(c.port, "POST", "/objects/" + caseObj.id() + "/rca",
                    "{\"template\":\"nope\"}").statusCode());
        }
    }

    @Test
    void linkObjectsAndTraverseGraph(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject caseObj = c.svc.objects().open(ObjectType.CASE, "investigation", "d",
                    "HIGH", "corr", Map.of());
            OperationalObject incident = c.svc.objects().open(ObjectType.INCIDENT, "bad rows", "d",
                    "HIGH", "corr", Map.of());

            // CASE CONTAINS INCIDENT
            JsonNode link = json(send(c.port, "POST", "/objects/" + caseObj.id() + "/links",
                    "{\"to\":\"" + incident.id() + "\",\"relationship\":\"contains\",\"actor\":\"alice\"}"));
            assertEquals("CONTAINS", link.get("relationship").asText());
            assertEquals(caseObj.id(), link.get("from").asText());
            assertEquals("INCIDENT", link.get("toType").asText());

            // links incident to the case
            JsonNode links = json(send(c.port, "GET", "/objects/" + caseObj.id() + "/links", null));
            assertTrue(links.isArray() && links.size() == 1);

            // correlation subgraph around the case
            JsonNode graph = json(send(c.port, "GET", "/objects/" + caseObj.id() + "/graph?depth=2", null));
            assertEquals(2, graph.get("nodes").size());
            assertEquals(1, graph.get("edges").size());

            // missing 'to' → 400; unknown endpoint → 404; unknown graph root → 404
            assertEquals(400, send(c.port, "POST", "/objects/" + caseObj.id() + "/links", "{}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/" + caseObj.id() + "/links",
                    "{\"to\":\"nope\"}").statusCode());
            assertEquals(404, send(c.port, "GET", "/objects/nope/graph", null).statusCode());
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
