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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** MET-4 Streams read-model: {@code GET /catalog/streams} lists each Source as a data-origin node. */
class ControlApiStreamsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void listsSourcesAsCatalogStreams(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/catalog/streams"))
                            .GET().build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals(1, data.size(), "one pipeline → one stream node");
            JsonNode stream = data.get(0);
            assertEquals("STREAM", stream.get("kind").asText());
            assertEquals(stream.get("id").asText(), stream.get("label").asText());
            assertEquals("local", stream.get("attrs").get("connector").asText());
            assertTrue(stream.get("description").get("text").asText().contains("collector feeding"),
                    stream.toString());
        } finally {
            api.close();
            svc.close();
        }
    }

    @Test
    void referenceProducingPipelineListsUnderReferencesNotStreams(@TempDir Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        Path refPipe = cfg.resolve("region_dim_pipeline.toon");
        Files.writeString(refPipe, minimalPipeline(cfg, "REGION_DIM", "produces: reference\n"));
        // an inactive (draft) stream pipeline — must still show, carrying active:false
        Path draftPipe = cfg.resolve("draft_stream_pipeline.toon");
        Files.writeString(draftPipe, minimalPipeline(cfg, "DRAFT_STREAM", ""));

        CollectorService svc = new CollectorService(List.of(pipe, refPipe, draftPipe), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            JsonNode streams = get(api.port(), "/api/v1/catalog/streams");
            assertEquals(2, streams.size(), "reference pipeline excluded from streams: " + streams);
            boolean sawLive = false, sawDraft = false;
            for (JsonNode n : streams) {
                String pipeline = n.get("attrs").get("pipeline").asText();
                boolean active = n.get("attrs").get("active").asBoolean();
                if ("mini_etl".equals(pipeline)) sawLive = active;
                if ("draft_stream".equals(pipeline)) sawDraft = !active;
            }
            assertTrue(sawLive, "active pipeline exposed with active:true: " + streams);
            assertTrue(sawDraft, "inactive draft listed with active:false: " + streams);

            JsonNode refs = get(api.port(), "/api/v1/catalog/references");
            boolean found = false;
            for (JsonNode n : refs) found |= "ref:region_dim".equals(n.get("id").asText());
            assertTrue(found, "produced Reference Dataset registered standalone: " + refs);
        } finally {
            api.close();
            svc.close();
        }
    }

    /** A minimal pipeline toon; {@code extraTopLevel} may inject e.g. {@code produces: reference}. */
    private static String minimalPipeline(Path dir, String name, String extraTopLevel) {
        String d = dir.toString().replace("\\", "/");
        return """
                name: %s
                %sversion: 1
                dirs:
                  poll: %s/%s_inbox
                  database: %s/%s_db
                output:
                  format: CSV
                processing:
                  threads: 1
                """.formatted(name, extraTopLevel, d, name.toLowerCase(), d, name.toLowerCase());
    }

    private static JsonNode get(int port, String path) throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }
}
