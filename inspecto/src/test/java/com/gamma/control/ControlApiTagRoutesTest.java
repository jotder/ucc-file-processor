package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.service.SourceService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tags + Tag Rules over real HTTP (GLOSSARY §9): the tag registry and rule CRUD behind the
 * fail-closed write gates (write-root 503 → unsafe/invalid 422 → duplicate 409), atomic
 * {@code *_tag.toon}/{@code *_tagrule.toon} persistence, bulk apply, and the create-time
 * auto-apply hook. Modeled on {@code ControlApiQueueRoutesTest} / {@code ControlApiConfigWriteTest}.
 */
class ControlApiTagRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
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
        SourceService svc = new SourceService(List.of(toon), 3600, 1);
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
            assertEquals(503, send(c.port, "POST", "/tags", "{\"name\":\"urgent\"}").statusCode());
            assertEquals(503, send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"r\",\"tag\":\"t\",\"filter\":{\"priority\":\"CRITICAL\"}}").statusCode());
            assertEquals(503, send(c.port, "DELETE", "/tags/rules/r", null).statusCode());
            assertEquals(200, send(c.port, "GET", "/tags", null).statusCode());
            assertEquals(200, send(c.port, "GET", "/tags/rules", null).statusCode());
        }
    }

    @Test
    void createValidatePersistAndListTags(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            JsonNode created = json(send(c.port, "POST", "/tags", "{\"name\":\"urgent\"}"));
            assertEquals("urgent", created.get("name").asText());
            assertTrue(Files.exists(writeRoot.resolve("urgent_tag.toon")), "tag persisted for the boot rescan");

            // gates: duplicate → 409; blank / comma / path-escaping names → 422
            assertEquals(409, send(c.port, "POST", "/tags", "{\"name\":\"urgent\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags", "{\"name\":\"  \"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags", "{\"name\":\"a,b\"}").statusCode());
            assertEquals(422, send(c.port, "POST", "/tags", "{\"name\":\"../evil\"}").statusCode());

            send(c.port, "POST", "/tags", "{\"name\":\"billing\"}");
            JsonNode list = json(send(c.port, "GET", "/tags", null));
            assertEquals(2, list.size());
            assertEquals("billing", list.get(0).get("name").asText(), "sorted by name");
            assertEquals("urgent", list.get(1).get("name").asText());
        }
    }

    @Test
    void tagRuleSaveApplyAutoApplyAndDelete(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            OperationalObject critical = c.svc.objects().open(ObjectType.INCIDENT, "rejected files spike", "d",
                    "HIGH", "CRITICAL", null, null, "corr", Map.of());
            c.svc.objects().open(ObjectType.INCIDENT, "minor glitch", "d", "LOW", "LOW", null, null, "corr", Map.of());

            // a rule without criteria would tag everything → 422
            assertEquals(422, send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"all\",\"tag\":\"x\",\"filter\":{}}").statusCode());

            // save; the rule's tag is implicitly registered and the rule is persisted
            JsonNode rule = json(send(c.port, "POST", "/tags/rules",
                    "{\"name\":\"critical-is-urgent\",\"tag\":\"urgent\",\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}}"));
            assertEquals("urgent", rule.get("tag").asText());
            assertTrue(Files.exists(writeRoot.resolve("critical-is-urgent_tagrule.toon")));
            assertEquals("urgent", json(send(c.port, "GET", "/tags", null)).get(0).get("name").asText(),
                    "saving a rule registers its tag");
            assertEquals(1, json(send(c.port, "GET", "/tags/rules", null)).size());

            // bulk apply: tags the matching incident once; idempotent on re-apply
            JsonNode applied = json(send(c.port, "POST", "/tags/rules/critical-is-urgent/apply", "{}"));
            assertEquals(1, applied.get("matched").asInt());
            assertEquals(1, applied.get("updated").asInt());
            JsonNode again = json(send(c.port, "POST", "/tags/rules/critical-is-urgent/apply", "{}"));
            assertEquals(1, again.get("matched").asInt());
            assertEquals(0, again.get("updated").asInt(), "idempotent — already tagged");
            JsonNode tagged = json(send(c.port, "GET", "/objects/" + critical.id(), null));
            assertEquals("urgent", tagged.get("attributes").get("tags").asText());

            // auto-apply: a new matching object is tagged at creation
            JsonNode created = json(send(c.port, "POST", "/objects",
                    "{\"title\":\"gateway down\",\"priority\":\"CRITICAL\"}"));
            assertEquals("urgent", created.get("attributes").get("tags").asText(),
                    "Tag Rules auto-apply on create (Gmail-filter semantics)");
            JsonNode nonMatch = json(send(c.port, "POST", "/objects",
                    "{\"title\":\"small thing\",\"priority\":\"LOW\"}"));
            assertFalse(nonMatch.get("attributes").has("tags"), "non-matching objects stay untagged");

            // unknown rule → 404 on apply; delete removes registry entry + file, then 404s
            assertEquals(404, send(c.port, "POST", "/tags/rules/ghost/apply", "{}").statusCode());
            JsonNode deleted = json(send(c.port, "DELETE", "/tags/rules/critical-is-urgent", null));
            assertTrue(deleted.get("fileRemoved").asBoolean());
            assertFalse(Files.exists(writeRoot.resolve("critical-is-urgent_tagrule.toon")));
            assertEquals(404, send(c.port, "DELETE", "/tags/rules/critical-is-urgent", null).statusCode());
            assertEquals(0, json(send(c.port, "GET", "/tags/rules", null)).size());
        }
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
