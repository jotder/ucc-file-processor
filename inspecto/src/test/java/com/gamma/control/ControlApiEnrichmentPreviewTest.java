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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /enrichment/preview} over real HTTP — the bounded, non-persisting enrichment preview that
 * gives the onboarding enrichment stage its "Validated" state. Runs the draft's transform over an inline
 * sample and returns {@code {columns, rows, truncated}}; stateless and un-gated like the parsing/schema
 * previews. Covers the happy path plus the 400 (missing config/sample) and 422 (bad transform) gates.
 */
class ControlApiEnrichmentPreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server with one pipeline and no enrichments; a preview needs no write root. */
    private Ctx open(Path configDir) throws Exception {
        Files.createDirectories(configDir);
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.clearProperty("assist.write.root");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** A minimal preview draft — input/output databases are ignored by a preview (it seeds from the sample). */
    private static String draftBody(String transform, String sampleRows) {
        return ("""
                {"config":{"name":"PREVIEW",
                   "input":{"database":"unused","format":"PARQUET","partitions":["day"]},
                   "output":{"database":"unused","format":"PARQUET","partitions":["day"]},
                   "transform":"%s"},
                 "sampleRows":%s}""").formatted(transform, sampleRows);
    }

    @Test
    void previewsADraftTransformOverASample(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir.resolve("cfg"))) {
            HttpResponse<String> resp = post(c.port, "/enrichment/preview", draftBody(
                    "SELECT id, UPPER(id) AS id_upper FROM input",
                    "[{\"id\":\"c1\"},{\"id\":\"c2\"},{\"id\":\"c3\"}]"));
            assertEquals(200, resp.statusCode(), resp.body());
            JsonNode r = JSON.readTree(resp.body());

            List<String> cols = new ArrayList<>();
            r.get("columns").forEach(n -> cols.add(n.asText()));
            assertEquals(List.of("id", "id_upper"), cols);
            assertEquals(3, r.get("rows").size());
            assertFalse(r.get("truncated").asBoolean());
            List<String> uppers = new ArrayList<>();
            r.get("rows").forEach(row -> uppers.add(row.get("id_upper").asText()));
            assertTrue(uppers.containsAll(List.of("C1", "C2", "C3")), "the transform ran over the sample: " + uppers);
        }
    }

    @Test
    void missingConfigOrSampleIs400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir.resolve("cfg"))) {
            assertEquals(400, post(c.port, "/enrichment/preview",
                    "{\"config\":{\"name\":\"x\",\"input\":{\"database\":\"u\",\"format\":\"PARQUET\",\"partitions\":[\"day\"]},"
                    + "\"output\":{\"database\":\"u\",\"format\":\"PARQUET\",\"partitions\":[\"day\"]},"
                    + "\"transform\":\"SELECT * FROM input\"}}").statusCode(), "no sampleRows");
            assertEquals(400, post(c.port, "/enrichment/preview",
                    "{\"sampleRows\":[{\"id\":\"c1\"}]}").statusCode(), "no config");
        }
    }

    @Test
    void aTransformThatFailsOnTheSampleIs422(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir.resolve("cfg"))) {
            HttpResponse<String> resp = post(c.port, "/enrichment/preview", draftBody(
                    "SELECT no_such_column FROM input", "[{\"id\":\"c1\"}]"));
            assertEquals(422, resp.statusCode(), resp.body());
            assertTrue(resp.body().contains("preview failed"), resp.body());
        }
    }
}
