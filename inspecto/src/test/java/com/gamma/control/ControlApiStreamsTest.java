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
}
