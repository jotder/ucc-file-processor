package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the v3.2.0 config-keystone routes over real HTTP (P4): the declarative
 * {@code GET /config/spec/{type}} (scope assist.read), and the extended {@code POST /validate} that
 * checks an unsaved draft against a spec with no file written — plus the back-compatible
 * {@code {"configPath"}} form.
 */
class ControlApiConfigSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String name) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "mini_etl");
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("GET", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void specFetchForEveryTypeAndUnknownIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            for (String type : List.of("pipeline", "enrichment", "job", "schema", "meta")) {
                HttpResponse<String> r = get(c.port, "/config/spec/" + type);
                assertEquals(200, r.statusCode(), type);
                JsonNode spec = JSON.readTree(r.body());
                assertEquals(type, spec.get("type").asText());
                assertTrue(spec.get("fields").size() > 0, type + " has fields");
            }
            assertEquals(404, get(c.port, "/config/spec/bogus").statusCode());
        }
    }

    @Test
    void specExposesCrossFieldRuleCatalogWithoutThePredicate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            JsonNode spec = JSON.readTree(get(c.port, "/config/spec/pipeline").body());
            JsonNode rules = spec.get("rules");
            assertTrue(rules.size() >= 1);
            JsonNode rule = rules.get(0);
            assertTrue(rule.has("id") && rule.has("description") && rule.has("severity"));
            assertFalse(rule.has("rule"), "the executable predicate must not serialise");
        }
    }

    @Test
    void validateDraftBodyReportsCrossFieldViolationWithoutAFile(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // ingester set but no segments → the plugin-ingester-requires-segments ERROR
            String body = """
                    {"type":"pipeline","config":{
                       "name":"X",
                       "dirs":{"poll":"/in","database":"/out"},
                       "processing":{"ingester":"com.x.Plugin","threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/validate", body);
            assertEquals(200, r.statusCode());
            JsonNode out = JSON.readTree(r.body());
            assertEquals("pipeline", out.get("type").asText());
            assertFalse(out.get("clean").asBoolean(), "violation present → not clean");
            boolean hasSegmentsErr = false;
            for (JsonNode f : out.get("findings"))
                if (f.get("message").asText().contains("segments")) hasSegmentsErr = true;
            assertTrue(hasSegmentsErr, "segments rule surfaced: " + out.get("findings"));
        }
    }

    @Test
    void validateCleanDraftHasNoFindings(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String body = """
                    {"type":"pipeline","config":{
                       "name":"X",
                       "dirs":{"poll":"/in","database":"/out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = JSON.readTree(post(c.port, "/validate", body).body());
            assertTrue(out.get("clean").asBoolean(), "clean draft: " + out.get("findings"));
            assertEquals(0, out.get("findings").size());
        }
    }

    @Test
    void safetyFlagSurfacesPathJailErrors(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // A UNC/network backup path is structurally a fine FILEPATH string (spec passes) but unsafe.
            String body = """
                    {"type":"pipeline","config":{
                       "name":"X",
                       "dirs":{"poll":"/in","database":"/out","backup":"//evil/share"},
                       "processing":{"threads":1}},
                     "safety":true}""";
            HttpResponse<String> r = post(c.port, "/validate", body);
            assertEquals(200, r.statusCode());
            JsonNode out = JSON.readTree(r.body());
            assertTrue(out.get("safetyChecked").asBoolean(), "safety gate ran");
            assertFalse(out.get("clean").asBoolean(), "unsafe path → not clean");
            boolean pathJail = false;
            for (JsonNode f : out.get("findings"))
                if (f.get("fieldPath").asText().contains("dirs.backup")) pathJail = true;
            assertTrue(pathJail, "path-jail ERROR surfaced: " + out.get("findings"));
        }
    }

    @Test
    void withoutSafetyFlagTheResponseIsUnchanged(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // Same unsafe path, but no safety flag → spec-only validation (FILEPATH is free text),
            // so no path-jail finding is injected and the gate is reported as not run.
            String body = """
                    {"type":"pipeline","config":{
                       "name":"X",
                       "dirs":{"poll":"/in","database":"/out","backup":"//evil/share"},
                       "processing":{"threads":1}}}""";
            JsonNode out = JSON.readTree(post(c.port, "/validate", body).body());
            assertFalse(out.get("safetyChecked").asBoolean(), "gate not run by default");
            assertTrue(out.get("clean").asBoolean(), "spec-only path is unchanged: " + out.get("findings"));
        }
    }

    @Test
    void validateBadDraftShapeIs400AndUnknownTypeIs404(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(400, post(c.port, "/validate", "{}").statusCode(),
                    "neither configPath nor type+config");
            assertEquals(404, post(c.port, "/validate",
                    "{\"type\":\"bogus\",\"config\":{}}").statusCode());
        }
    }

    @Test
    void legacyConfigPathFormStillWorksAndAddsFindings(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path toon = c.svc.pathFor(c.name).orElseThrow();
            String body = "{\"configPath\":\"" + toon.toString().replace("\\", "/") + "\"}";
            HttpResponse<String> r = post(c.port, "/validate", body);
            assertEquals(200, r.statusCode());
            JsonNode out = JSON.readTree(r.body());
            assertEquals(c.name, out.get("pipeline").asText());
            assertTrue(out.has("warnings"), "legacy warnings field preserved");
            assertTrue(out.has("findings"), "structured findings added");
        }
    }
}
