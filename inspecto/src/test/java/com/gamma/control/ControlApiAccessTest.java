package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
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
 * Lens access configuration over real HTTP ({@code AccessRoutes} —
 * {@code docs/superpower/lens-access-config-design.md}): the Access Catalog singleton and the
 * per-subject Access Profiles, behind the shared write gates (503 no write root, 422 bad body/name,
 * 404 unknown profile), with reads staying open.
 */
class ControlApiAccessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String CATALOG = """
            {"version":1,"nodes":[
              {"id":"business-group","label":"Business","kind":"menu","children":[
                {"id":"requirements","label":"Requirements","kind":"pane","link":"/requirements","children":[
                  {"id":"requirements.triage","label":"Triage requirements","kind":"action",
                   "capability":"canTriageRequirements"}]}]},
              {"id":"settings","label":"Settings","kind":"pane","link":"/settings"}]}""";

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        if (writeRoot != null) {
            Files.createDirectories(writeRoot);
            System.setProperty("assist.write.root", writeRoot.toString());
        } else {
            System.clearProperty("assist.write.root");
        }
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    @Test
    void writesFailClosedWithoutWriteRootButReadsStayOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(503, send(c.port, "PUT", "/access/catalog", CATALOG).statusCode());
            assertEquals(503, send(c.port, "PUT", "/access/profiles/lens-business",
                    "{\"subjectType\":\"lens\",\"subjectId\":\"business\"}").statusCode());
            assertEquals(503, send(c.port, "DELETE", "/access/profiles/lens-business", null).statusCode());

            JsonNode catalog = json(send(c.port, "GET", "/access/catalog", null));
            assertEquals(0, catalog.get("version").asInt(), "unsaved catalog is the empty one, not an error");
            assertTrue(catalog.get("nodes").isEmpty());
            assertTrue(json(send(c.port, "GET", "/access/profiles", null)).isEmpty());
        }
    }

    @Test
    void catalogValidatesShapeAndRoundTrips(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            // shape gates → 422: no nodes list · unknown kind · action without capability · duplicate id
            assertEquals(422, send(c.port, "PUT", "/access/catalog", "{}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/catalog",
                    "{\"nodes\":[{\"id\":\"a\",\"label\":\"A\",\"kind\":\"folder\"}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/catalog",
                    "{\"nodes\":[{\"id\":\"a\",\"label\":\"A\",\"kind\":\"action\"}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/catalog",
                    "{\"nodes\":[{\"id\":\"a\",\"label\":\"A\",\"kind\":\"pane\"},"
                            + "{\"id\":\"a\",\"label\":\"B\",\"kind\":\"pane\"}]}").statusCode());

            JsonNode saved = json(send(c.port, "PUT", "/access/catalog", CATALOG));
            assertEquals(1, saved.get("version").asInt());
            assertTrue(Files.exists(writeRoot.resolve("registry/access-catalog/catalog.toon")));

            JsonNode read = json(send(c.port, "GET", "/access/catalog", null));
            assertEquals(2, read.get("nodes").size());
            JsonNode action = read.get("nodes").get(0).get("children").get(0).get("children").get(0);
            assertEquals("requirements.triage", action.get("id").asText());
            assertEquals("canTriageRequirements", action.get("capability").asText());
        }
    }

    @Test
    void profileGatesRoundTripAndDelete(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            // 422 gates: unsafe id · bad subjectType · id/subject mismatch · bad grant value
            assertEquals(422, send(c.port, "PUT", "/access/profiles/x..y",
                    "{\"subjectType\":\"lens\",\"subjectId\":\"x..y\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/profiles/user-alice",
                    "{\"subjectType\":\"user\",\"subjectId\":\"alice\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/profiles/lens-business",
                    "{\"subjectType\":\"lens\",\"subjectId\":\"builder\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/profiles/lens-business",
                    "{\"subjectType\":\"lens\",\"subjectId\":\"business\","
                            + "\"grants\":{\"settings\":\"maybe\"}}").statusCode());

            // upsert → persisted for the boot rescan, listed, grants normalized through
            JsonNode saved = json(send(c.port, "PUT", "/access/profiles/lens-business",
                    "{\"subjectType\":\"lens\",\"subjectId\":\"business\",\"label\":\"Business\","
                            + "\"grants\":{\"workbench.author\":\"deny\",\"settings\":\"allow\"}}"));
            assertEquals("deny", saved.get("grants").get("workbench.author").asText());
            assertTrue(Files.exists(writeRoot.resolve("registry/access-profiles/lens-business.toon")));

            JsonNode listed = json(send(c.port, "GET", "/access/profiles", null));
            assertEquals(1, listed.size());
            assertEquals("business", listed.get(0).get("subjectId").asText());

            // update replaces grants; a grant may reference an id absent from the catalog (forward-compat)
            JsonNode updated = json(send(c.port, "PUT", "/access/profiles/lens-business",
                    "{\"subjectType\":\"lens\",\"subjectId\":\"business\","
                            + "\"grants\":{\"not.in.catalog.yet\":\"deny\"}}"));
            assertNull(updated.get("grants").get("workbench.author"));
            assertEquals("deny", updated.get("grants").get("not.in.catalog.yet").asText());

            assertEquals("lens-business",
                    json(send(c.port, "DELETE", "/access/profiles/lens-business", null)).get("deleted").asText());
            assertFalse(Files.exists(writeRoot.resolve("registry/access-profiles/lens-business.toon")));
            assertEquals(404, send(c.port, "DELETE", "/access/profiles/lens-business", null).statusCode());
        }
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), () -> "expected 200 but got " + r.statusCode() + ": " + r.body());
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
