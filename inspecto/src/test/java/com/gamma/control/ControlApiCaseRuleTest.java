package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule-raised cases + case analytics over real HTTP (C5/C4): {@code /cases/rules} CRUD behind the
 * write gates (503/422/404), {@code POST /cases/rules/{name}/evaluate} auto-grouping, and
 * {@code GET /objects/analytics?type=CASE}.
 */
class ControlApiCaseRuleTest {

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
    void writesFailClosedWithoutWriteRootButReadsStayOpen(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            assertEquals(503, send(c.port, "POST", "/cases/rules",
                    "{\"name\":\"r\",\"title\":\"t\",\"filter\":{\"priority\":\"CRITICAL\"}}").statusCode());
            assertEquals(503, send(c.port, "DELETE", "/cases/rules/r", null).statusCode());
            assertEquals(200, send(c.port, "GET", "/cases/rules", null).statusCode());
        }
    }

    @Test
    void saveEvaluateAndDeleteACaseRule(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            // a rule with no filter criterion → 422
            assertEquals(422, send(c.port, "POST", "/cases/rules",
                    "{\"name\":\"bad\",\"title\":\"t\",\"filter\":{}}").statusCode());

            JsonNode saved = json(send(c.port, "POST", "/cases/rules",
                    "{\"name\":\"crit\",\"title\":\"Critical cluster\",\"threshold\":2,\"windowMinutes\":1440,"
                            + "\"filter\":{\"type\":\"INCIDENT\",\"priority\":\"CRITICAL\"}}"));
            assertEquals("Critical cluster", saved.get("title").asText());
            assertTrue(Files.exists(writeRoot.resolve("crit_caserule.toon")), "persisted for the boot rescan");
            assertEquals(1, json(send(c.port, "GET", "/cases/rules", null)).size());

            // below threshold → nothing raised
            c.svc.objects().open(ObjectType.INCIDENT, "one", "d", "HIGH", "CRITICAL", null, null, "k", Map.of());
            JsonNode below = json(send(c.port, "POST", "/cases/rules/crit/evaluate", "{}"));
            assertEquals(1, below.get("matched").asInt());
            assertEquals(0, below.get("grouped").asInt());
            assertTrue(below.get("caseId").isNull());

            // threshold met → a case is opened grouping both incidents
            c.svc.objects().open(ObjectType.INCIDENT, "two", "d", "HIGH", "CRITICAL", null, null, "k", Map.of());
            JsonNode raised = json(send(c.port, "POST", "/cases/rules/crit/evaluate", "{}"));
            assertEquals(2, raised.get("grouped").asInt());
            assertTrue(raised.get("opened").asBoolean());
            String caseId = raised.get("caseId").asText();
            JsonNode links = json(send(c.port, "GET", "/objects/" + caseId + "/links", null));
            assertEquals(2, count(links, caseId, "CONTAINS"));

            // unknown rule evaluate → 404; delete removes registry + file
            assertEquals(404, send(c.port, "POST", "/cases/rules/ghost/evaluate", "{}").statusCode());
            assertTrue(json(send(c.port, "DELETE", "/cases/rules/crit", null)).get("fileRemoved").asBoolean());
            assertFalse(Files.exists(writeRoot.resolve("crit_caserule.toon")));
            assertEquals(404, send(c.port, "DELETE", "/cases/rules/crit", null).statusCode());
        }
    }

    @Test
    void caseAnalyticsRollup(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            c.svc.objects().open(ObjectType.CASE, "a", "d", "HIGH", "MAJOR", null, null, "k",
                    Map.of("category", "Security / Data / Leak", "impactAmount", "1000", "recordsAffected", "50"));
            c.svc.objects().open(ObjectType.CASE, "b", "d", "HIGH", "LOW", null, null, "k",
                    Map.of("category", "Pipeline / Ingest / Parse", "impactAmount", "500"));

            JsonNode a = json(send(c.port, "GET", "/objects/analytics?type=CASE", null));
            assertEquals("CASE", a.get("type").asText());
            assertEquals(2, a.get("total").asInt());
            assertEquals(2, a.get("backlog").asInt(), "both cases are non-terminal");
            assertEquals(1, a.get("byCategory").get("Security").asInt());
            assertEquals(1500.0, a.get("impact").get("impactAmount").asDouble(), 0.001);
            assertEquals(50, a.get("impact").get("recordsAffected").asLong());
            assertEquals(400, send(c.port, "GET", "/objects/analytics?type=bogus", null).statusCode());
        }
    }

    private static int count(JsonNode links, String from, String rel) {
        int n = 0;
        for (JsonNode l : links)
            if (from.equals(l.get("from").asText()) && rel.equals(l.get("relationship").asText())) n++;
        return n;
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
