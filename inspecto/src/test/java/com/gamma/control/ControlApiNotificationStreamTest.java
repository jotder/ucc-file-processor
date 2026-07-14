package com.gamma.control;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the SSE real-time stream ({@code GET /notifications/stream}): a notification
 * produced after the client connects is pushed as a {@code data:} frame without any polling.
 */
class ControlApiNotificationStreamTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @Test
    void pushesNotificationOverSse(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("http://localhost:" + c.port + "/notifications/stream")).GET().build();
            HttpResponse<InputStream> resp = client.send(req, BodyHandlers.ofInputStream());

            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"));

            // Listener is registered before the response headers are committed, so firing now is safe.
            c.svc.notificationService().onEvent(Event.builder(EventType.BATCH_FAILED)
                    .pipeline("orders").correlationId("b1").message("boom").build());

            BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
            String dataLine = CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) return line;
                    }
                } catch (Exception ignore) { /* stream closed */ }
                return null;
            }).get(5, TimeUnit.SECONDS);

            assertNotNull(dataLine, "a data: frame arrived over SSE");
            assertTrue(dataLine.contains("Pipeline orders failed"), "frame carries the rendered notification");
            resp.body().close();
        }
    }
}
