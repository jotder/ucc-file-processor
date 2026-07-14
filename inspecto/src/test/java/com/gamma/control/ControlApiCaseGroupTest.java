package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.ops.link.LinkRelationship;
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
 * Case group management over real HTTP (GLOSSARY §9): {@code POST /objects/{id}/merge},
 * {@code POST /objects/{id}/split} and {@code DELETE /objects/{id}/links}, with the error gates the
 * routes map (400 bad body, 404 unknown id/link, 422 illegal participants). Objects are seeded
 * through the service API, then exercised over the wire.
 */
class ControlApiCaseGroupTest {

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
    void mergeOverHttpMovesMembersAndClosesTheSource(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject survivor = c.svc.objects().open(ObjectType.CASE, "ring A", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject source = c.svc.objects().open(ObjectType.CASE, "ring A dup", "d", "HIGH", null, null, null, "corr",
                    Map.of("tags", "billing"));
            OperationalObject member = c.svc.objects().open(ObjectType.INCIDENT, "i", "d", "HIGH", null, null, null, "corr", Map.of());
            c.svc.objects().link(source.id(), member.id(), LinkRelationship.CONTAINS, null);

            // gates first: empty sources → 400; unknown survivor → 404; a non-CASE source → 422
            assertEquals(400, send(c.port, "POST", "/objects/" + survivor.id() + "/merge", "{\"sources\":[]}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/merge",
                    "{\"sources\":[\"" + source.id() + "\"]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/objects/" + survivor.id() + "/merge",
                    "{\"sources\":[\"" + member.id() + "\"]}").statusCode());

            JsonNode out = json(send(c.port, "POST", "/objects/" + survivor.id() + "/merge",
                    "{\"sources\":[\"" + source.id() + "\"],\"actor\":\"op\"}"));
            assertEquals(1, out.get("membersMoved").asInt());
            assertEquals(source.id(), out.get("merged").get(0).asText());
            assertTrue(out.get("survivor").get("attributes").get("tags").asText().contains("billing"), "tags union");

            JsonNode closed = json(send(c.port, "GET", "/objects/" + source.id(), null));
            assertEquals("CLOSED", closed.get("status").asText());
            assertEquals(survivor.id(), closed.get("attributes").get("mergedInto").asText());

            // absorbed again → 422
            assertEquals(422, send(c.port, "POST", "/objects/" + survivor.id() + "/merge",
                    "{\"sources\":[\"" + source.id() + "\"]}").statusCode());
        }
    }

    @Test
    void splitOverHttpCarvesANewCaseAndDeleteLinkRemovesMembers(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            OperationalObject original = c.svc.objects().open(ObjectType.CASE, "big case", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject i1 = c.svc.objects().open(ObjectType.INCIDENT, "one", "d", "HIGH", null, null, null, "corr", Map.of());
            OperationalObject i2 = c.svc.objects().open(ObjectType.INCIDENT, "two", "d", "HIGH", null, null, null, "corr", Map.of());
            c.svc.objects().link(original.id(), i1.id(), LinkRelationship.CONTAINS, null);
            c.svc.objects().link(original.id(), i2.id(), LinkRelationship.CONTAINS, null);

            // gates: no title → 400; foreign member → 422; unknown case → 404
            assertEquals(400, send(c.port, "POST", "/objects/" + original.id() + "/split",
                    "{\"members\":[\"" + i1.id() + "\"]}").statusCode());
            assertEquals(422, send(c.port, "POST", "/objects/" + original.id() + "/split",
                    "{\"title\":\"x\",\"members\":[\"nope\"]}").statusCode());
            assertEquals(404, send(c.port, "POST", "/objects/nope/split",
                    "{\"title\":\"x\",\"members\":[\"" + i1.id() + "\"]}").statusCode());

            JsonNode out = json(send(c.port, "POST", "/objects/" + original.id() + "/split",
                    "{\"title\":\"part B\",\"members\":[\"" + i1.id() + "\"],\"actor\":\"op\"}"));
            assertEquals(1, out.get("membersMoved").asInt());
            String partId = out.get("case").get("id").asText();
            assertEquals("part B", out.get("case").get("title").asText());

            // the new part contains i1; the original keeps i2; SPLIT_FROM trace present
            JsonNode partLinks = json(send(c.port, "GET", "/objects/" + partId + "/links", null));
            assertTrue(hasEdge(partLinks, partId, i1.id(), "CONTAINS"));
            assertTrue(hasEdge(partLinks, partId, original.id(), "SPLIT_FROM"));
            JsonNode origLinks = json(send(c.port, "GET", "/objects/" + original.id() + "/links", null));
            assertFalse(hasEdge(origLinks, original.id(), i1.id(), "CONTAINS"));
            assertTrue(hasEdge(origLinks, original.id(), i2.id(), "CONTAINS"));

            // DELETE link: missing to → 400; unknown edge → 404; then a real removal succeeds once
            assertEquals(400, send(c.port, "DELETE", "/objects/" + original.id() + "/links", null).statusCode());
            assertEquals(404, send(c.port, "DELETE", "/objects/" + original.id()
                    + "/links?to=ghost&relationship=CONTAINS", null).statusCode());
            assertEquals(200, send(c.port, "DELETE", "/objects/" + original.id()
                    + "/links?to=" + i2.id() + "&relationship=CONTAINS", null).statusCode());
            assertEquals(404, send(c.port, "DELETE", "/objects/" + original.id()
                    + "/links?to=" + i2.id() + "&relationship=CONTAINS", null).statusCode(), "already removed");
        }
    }

    private static boolean hasEdge(JsonNode links, String from, String to, String rel) {
        for (JsonNode l : links) {
            if (from.equals(l.get("from").asText()) && to.equals(l.get("to").asText())
                    && rel.equals(l.get("relationship").asText())) return true;
        }
        return false;
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
