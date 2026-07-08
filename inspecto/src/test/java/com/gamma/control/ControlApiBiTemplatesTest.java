package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SourceService;
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

/** BI-8 template gallery: listing, parameterized apply, all-or-nothing conflict handling. */
class ControlApiBiTemplatesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SourceService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            SourceService svc = new SourceService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    @Test
    void galleryListsAndAppliesParameterized(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            ComponentStore reg = new ComponentStore(root.resolve("registry"));
            reg.write("dataset", "sales_ds", Map.of("physicalRef", "sales"));

            HttpResponse<String> gallery = client.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/api/v1/bi/templates")).GET().build(),
                    BodyHandlers.ofString());
            assertEquals(200, gallery.statusCode());
            JsonNode list = JSON.readTree(gallery.body()).get("data");
            assertTrue(list.size() >= 2, "curated gallery ships at least two templates");

            HttpResponse<String> applied = post(c.port, "/api/v1/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"sales_ds\"}");
            assertEquals(200, applied.statusCode(), applied.body());
            assertEquals(4, JSON.readTree(applied.body()).at("/data/created").size(),
                    "3 widgets + 1 dashboard");

            // The applied dashboard is a real, editable component bound to the caller's dataset.
            Map<String, Object> widget = reg.get("widget", "sum_by_dim").orElseThrow().content();
            assertEquals("sales_ds", widget.get("datasetId"));
            assertTrue(reg.get("dashboard", "kpi_board").isPresent());

            // Re-apply without a prefix → 409 (all-or-nothing); with a prefix → fresh ids.
            assertEquals(409, post(c.port, "/api/v1/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"sales_ds\"}").statusCode());
            assertEquals(200, post(c.port, "/api/v1/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"sales_ds\",\"prefix\":\"q3\"}").statusCode());
            assertTrue(reg.get("dashboard", "q3_kpi_board").isPresent());
        }
    }

    @Test
    void applyFailsClosed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(404, post(c.port, "/api/v1/bi/templates/ghost/apply",
                    "{\"dataset\":\"x\"}").statusCode());
            assertEquals(422, post(c.port, "/api/v1/bi/templates/kpi-overview/apply", "{}").statusCode());
            assertEquals(404, post(c.port, "/api/v1/bi/templates/kpi-overview/apply",
                    "{\"dataset\":\"missing_ds\"}").statusCode(), "unknown dataset");
        }
    }
}
