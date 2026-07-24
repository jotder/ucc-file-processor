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
 * Access Policies over real HTTP ({@code GET/PUT /access/policies} — ABAC A2,
 * {@code docs/superpower/rbac-abac-plan.md} §4): authorable allow/deny statements whose {@code when}
 * conditions parse-gate at authoring time ({@code Conditions} → 422, never a stored time bomb),
 * behind the shared write gates. Evaluation is the Enterprise policy engine's job (A3) — these routes
 * only author and serve the documents.
 */
class ControlApiAccessPoliciesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

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
    void readsServeTheEmptyDocAndWritesFailClosedWithoutWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            JsonNode doc = json(send(c.port, "GET", "/access/policies", null));
            assertTrue(doc.get("policies").isEmpty(), "nothing authored → an empty list, not an error");
            assertNull(doc.get("error"));
            assertEquals(503, send(c.port, "PUT", "/access/policies", "{\"policies\":[]}").statusCode());
        }
    }

    @Test
    void putValidatesShapeAndParseGatesTheCondition(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("cfg"))) {
            // 422 gates: no policies list · non-object entry · unsafe/duplicate name · bad effect ·
            // bad target shape · unknown action · blank resourceKind
            assertEquals(422, send(c.port, "PUT", "/access/policies", "{}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies", "{\"policies\":[\"x\"]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"a..b\",\"effect\":\"deny\"}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"deny\"},{\"name\":\"X\",\"effect\":\"allow\"}]}")
                    .statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"block\"}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"deny\",\"target\":\"objects\"}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"deny\",\"target\":{\"actions\":[\"delete\"]}}]}")
                    .statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"deny\",\"target\":{\"resourceKinds\":[\" \"]}}]}")
                    .statusCode());

            // the Conditions parse gate: a broken `when` is a 422 whose message carries the offset
            HttpResponse<String> bad = send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"deny\",\"when\":\"resource.space ==\"}]}");
            assertEquals(422, bad.statusCode());
            assertTrue(bad.body().contains("syntax error"), () -> "expected a parse message: " + bad.body());
        }
    }

    @Test
    void roundTripPersistsAndEchoesTheAuthoredPolicies(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            JsonNode saved = json(send(c.port, "PUT", "/access/policies", """
                    {"policies":[
                      {"name":"Cross-Space-Deny","effect":"deny",
                       "when":"resource.space != subject.space"},
                      {"name":"pii-writes","effect":"deny",
                       "target":{"actions":["write"],"resourceKinds":["Dataset"]},
                       "when":"resource.tags contains 'pii'"}]}"""));
            assertTrue(Files.exists(writeRoot.resolve("access-policies.toon")));

            JsonNode cross = byName(saved.get("policies"), "cross-space-deny");   // name lower-cased
            assertEquals("deny", cross.get("effect").asText());
            assertNull(cross.get("target"), "no target → unconstrained, key omitted");
            assertEquals("resource.space != subject.space", cross.get("when").asText());

            JsonNode pii = byName(saved.get("policies"), "pii-writes");
            assertEquals("write", pii.get("target").get("actions").get(0).asText());
            assertEquals("dataset", pii.get("target").get("resourceKinds").get(0).asText(),
                    "resource kinds lower-cased");

            // survives the round trip through disk (fresh GET re-reads the TOON)
            JsonNode reread = json(send(c.port, "GET", "/access/policies", null));
            assertEquals(2, reread.get("policies").size());

            // full replace with an empty list clears the doc
            JsonNode cleared = json(send(c.port, "PUT", "/access/policies", "{\"policies\":[]}"));
            assertTrue(cleared.get("policies").isEmpty());
        }
    }

    @Test
    void authoredPoliciesCarryAnAuthoredSourceBadge(@TempDir Path dir) throws Exception {
        // Seed rows only appear when the Enterprise engine supplies them (ControlApiPolicyEnforcementTest);
        // on the core/Personal classpath every row is authored, tagged so the UI can render source badges.
        try (Ctx c = open(dir, dir.resolve("cfg"))) {
            JsonNode saved = json(send(c.port, "PUT", "/access/policies",
                    "{\"policies\":[{\"name\":\"x\",\"effect\":\"deny\",\"when\":\"subject.id == 'nobody'\"}]}"));
            assertEquals(1, saved.get("policies").size());
            assertEquals("authored", saved.get("policies").get(0).get("source").asText());
        }
    }

    @Test
    void explainReportsDisabledWithoutAPolicyEngine(@TempDir Path dir) throws Exception {
        // Core/Personal ship no AccessDecider — /access/explain is a no-op that says so, never a 500.
        try (Ctx c = open(dir, null)) {
            JsonNode r = json(send(c.port, "GET", "/access/explain?route=/objects", null));
            assertFalse(r.get("enabled").asBoolean());
            assertTrue(r.get("reason").asText().contains("no access policy engine"), r.get("reason").asText());
        }
    }

    @Test
    void unreadableOnDiskDocIsSurfacedFailClosed(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            // authoring-time 422s make this unreachable via the API — only a broken on-disk edit
            Files.writeString(writeRoot.resolve("access-policies.toon"), "policies: [ this is not valid");
            JsonNode doc = json(send(c.port, "GET", "/access/policies", null));
            assertTrue(doc.get("policies").isEmpty());
            assertTrue(doc.get("error").asText().contains("fail-closed"),
                    "the engine must deny loudly, never treat damage as 'no policies'");
        }
    }

    private static JsonNode byName(JsonNode policies, String name) {
        for (JsonNode p : policies) if (name.equals(p.get("name").asText())) return p;
        return null;
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
