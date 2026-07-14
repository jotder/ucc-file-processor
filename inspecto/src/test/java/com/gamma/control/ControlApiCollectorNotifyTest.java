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
 * Real-HTTP tests for {@code POST /collectors/{id}/notify} (ACQ-6 push discovery): v1 202+runId+Location with
 * the {@code notify} trigger label, unchanged legacy 200, 404 for an unknown source id, and the audit
 * classification. Mirrors the {@code /runs/{name}/trigger} coverage in {@link ControlApiAsyncV1Test}.
 */
class ControlApiCollectorNotifyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** The registered pipeline's source id, read the way an external notifier would: {@code GET /collectors}. */
    private String sourceId(Ctx c) throws Exception {
        HttpResponse<String> sources = get(c.port, "/collectors");
        assertEquals(200, sources.statusCode());
        return JSON.readTree(sources.body()).get(0).get("id").asText();
    }

    private HttpResponse<String> post(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method("POST", BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                BodyHandlers.ofString());
    }

    @Test
    void notifyIsAcceptedWithRunIdAndNotifyTrigger(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            String source = sourceId(c);
            HttpResponse<String> accepted = post(c.port, "/api/v1/collectors/" + source + "/notify");
            assertEquals(202, accepted.statusCode(), accepted.body());
            JsonNode data = JSON.readTree(accepted.body()).get("data");
            String runId = data.get("runId").asText();
            assertFalse(runId.isBlank());
            assertEquals(source, data.get("source").asText());
            assertEquals("running", data.get("status").asText());
            assertEquals("/api/v1/runs/runs/" + runId, accepted.headers().firstValue("Location").orElse(null));

            // Poll to terminal; the run is labelled with the notify trigger, not manual.
            String status = null;
            JsonNode run = null;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                HttpResponse<String> polled = get(c.port, "/api/v1/runs/runs/" + runId);
                assertEquals(200, polled.statusCode(), polled.body());
                run = JSON.readTree(polled.body()).get("data");
                status = run.get("status").asText();
                if (!"RUNNING".equals(status)) break;
                Thread.sleep(50);
            }
            assertEquals("SUCCESS", status, "the empty-inbox notify run completes without failures");
            assertEquals("notify", run.get("trigger").asText());
        }
    }

    @Test
    void legacyNotifyIsSynchronousRunResult(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            HttpResponse<String> r = post(c.port, "/collectors/" + sourceId(c) + "/notify");   // unversioned surface
            assertEquals(200, r.statusCode(), r.body());
            JsonNode body = JSON.readTree(r.body());
            assertTrue(body.has("total") && body.has("failed"), "legacy body is the raw RunResult");
        }
    }

    @Test
    void unknownSourceIs404(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {
            assertEquals(404, post(c.port, "/api/v1/collectors/ghost/notify").statusCode());
            assertEquals(404, post(c.port, "/collectors/ghost/notify").statusCode());
        }
    }

    @Test
    void notifyIsAuditedAsCollectorMutation() {
        AuditTrail.Action a = AuditTrail.classify("POST", "/collectors/feed-a/notify");
        assertNotNull(a);
        assertEquals("collector.notified", a.name());
        assertEquals("data_mutation", a.category());
    }
}
