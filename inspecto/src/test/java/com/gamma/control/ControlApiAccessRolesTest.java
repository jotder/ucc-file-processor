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
 * Role settings over real HTTP ({@code GET/PUT /access/roles} — RBAC R1,
 * {@code docs/superpower/rbac-abac-plan.md} §3): the authorable role → capability/data-scope table
 * ({@link Roles}, a {@code roles.toon} settings doc overlaying the shipped seed), behind the shared
 * write gates (503 no write root, 422 bad shape/name/capability), with reads staying open and
 * serving the seed when nothing is authored.
 */
class ControlApiAccessRolesTest {

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
    void readsServeTheSeedAndWritesFailClosedWithoutWriteRoot(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            JsonNode roles = json(send(c.port, "GET", "/access/roles", null)).get("roles");
            assertFalse(roles.isEmpty(), "no write root → the seed table, not an error");
            JsonNode admin = byName(roles, "admin");
            assertEquals("seed", admin.get("source").asText());
            assertTrue(contains(admin.get("capabilities"), "canConfigureAccess"),
                    "the seed must grant canConfigureAccess somewhere, else roles.toon could never be authored");

            assertEquals(503, send(c.port, "PUT", "/access/roles", "{\"roles\":[]}").statusCode());
        }
    }

    @Test
    void putValidatesShape(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, dir.resolve("cfg"))) {
            // 422 gates: no roles list · non-object entry · unsafe name · duplicate name ·
            // unknown capability · non-list dataScopes · blank scope
            assertEquals(422, send(c.port, "PUT", "/access/roles", "{}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles", "{\"roles\":[\"admin\"]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[{\"name\":\"a..b\",\"capabilities\":[]}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[{\"name\":\"x\",\"capabilities\":[]},{\"name\":\"X\",\"capabilities\":[]}]}")
                    .statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[{\"name\":\"x\",\"capabilities\":[\"canDoAnything\"]}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[{\"name\":\"x\",\"dataScopes\":\"fraud\"}]}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[{\"name\":\"x\",\"dataScopes\":[\" \"]}]}").statusCode());
        }
    }

    @Test
    void roundTripOverlaysTheSeedPerRoleName(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            JsonNode saved = json(send(c.port, "PUT", "/access/roles", """
                    {"roles":[
                      {"name":"Auditor","capabilities":["canOperateRuns"],"dataScopes":["Fraud"]},
                      {"name":"developer","capabilities":[]}]}"""));
            assertTrue(Files.exists(writeRoot.resolve("roles.toon")));

            // authored custom role: name lower-cased, scopes lower-cased, marked authored
            JsonNode auditor = byName(saved.get("roles"), "auditor");
            assertEquals("authored", auditor.get("source").asText());
            assertTrue(contains(auditor.get("capabilities"), "canOperateRuns"));
            assertEquals("fraud", auditor.get("dataScopes").get(0).asText());

            // authored seed override: developer revoked (empty capabilities win over the seed)
            JsonNode developer = byName(saved.get("roles"), "developer");
            assertEquals("authored", developer.get("source").asText());
            assertTrue(developer.get("capabilities").isEmpty());

            // untouched seed roles keep their defaults alongside the authored doc
            JsonNode admin = byName(saved.get("roles"), "admin");
            assertEquals("seed", admin.get("source").asText());
            assertTrue(contains(admin.get("capabilities"), "canOnboardConnections"));

            // an empty authored list clears every override — pure seed again
            JsonNode cleared = json(send(c.port, "PUT", "/access/roles", "{\"roles\":[]}"));
            assertEquals("seed", byName(cleared.get("roles"), "developer").get("source").asText());
            assertNull(byName(cleared.get("roles"), "auditor"), "custom role gone with its doc entry");
        }
    }

    @Test
    void identityAttributeClaimsRideTheSameDoc(@TempDir Path dir) throws Exception {
        // ABAC A1: the identity.attributeClaims allowlist (which verified IdP claims may surface as
        // Subject.attributes()) is authored on the same settings doc — full-replace semantics apply.
        try (Ctx c = open(dir, dir.resolve("cfg"))) {
            JsonNode saved = json(send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[],\"identity\":{\"attributeClaims\":[\"department\",\"clearance\"]}}"));
            assertEquals("department", saved.get("identity").get("attributeClaims").get(0).asText());
            assertEquals("clearance", saved.get("identity").get("attributeClaims").get(1).asText());

            // 422 gates: identity not an object · attributeClaims not a list · blank claim
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[],\"identity\":\"department\"}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[],\"identity\":{\"attributeClaims\":\"department\"}}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/access/roles",
                    "{\"roles\":[],\"identity\":{\"attributeClaims\":[\" \"]}}").statusCode());

            // full replace without an identity block clears the allowlist (no attributes, ever)
            JsonNode cleared = json(send(c.port, "PUT", "/access/roles", "{\"roles\":[]}"));
            assertNull(cleared.get("identity"));
        }
    }

    private static JsonNode byName(JsonNode roles, String name) {
        for (JsonNode r : roles) if (name.equals(r.get("name").asText())) return r;
        return null;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) if (value.equals(n.asText())) return true;
        return false;
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
