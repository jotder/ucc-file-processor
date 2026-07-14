package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the security audit trail emitted centrally from {@link ControlApi#dispatch}:
 * a state-changing request produces an append-only {@code AUDIT} event carrying actor/action/ip; the
 * append-only event routes reject mutation methods (405 immutability guard); and a non-GET attempt at
 * an unknown route is recorded as {@code ACCESS_DENIED}.
 */
class ControlApiAuditTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String name) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-02-05\n");
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), "test_etl");
    }

    @Test
    void mutatingRequestEmitsAuditEvent(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "POST", "/runs/" + c.name + "/trigger", null).statusCode());

            JsonNode events = json(send(c.port, "GET", "/events?limit=200", null));
            JsonNode audit = null;
            for (JsonNode e : events) {
                // The default space's event store is process-global (SpaceRoot.legacy()), so /events can carry
                // pipeline.triggered audits from other integration tests — match this run's own pipeline by id.
                JsonNode a = e.get("attributes");
                if ("AUDIT".equals(e.get("type").asText())
                        && "pipeline.triggered".equals(a.get("action").asText())
                        && c.name.equals(a.path("target_id").asText())) {
                    audit = e;
                }
            }
            assertNotNull(audit, "a pipeline.triggered AUDIT event was recorded");
            JsonNode attrs = audit.get("attributes");
            assertEquals("appUser", attrs.get("actor").asText(), "auth-free default actor");
            assertEquals("data_mutation", attrs.get("action_category").asText());
            assertEquals("pipeline", attrs.get("target_type").asText());
            assertEquals(c.name, attrs.get("target_id").asText());
            assertFalse(attrs.get("ip").asText().isBlank(), "client ip captured");
        }
    }

    @Test
    void honoursCustomActorHeader(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + c.port + "/runs/" + c.name + "/pause"))
                    .header("X-Actor", "support_agent")
                    .POST(BodyPublishers.noBody()).build();
            assertEquals(200, client.send(req, BodyHandlers.ofString()).statusCode());

            JsonNode events = json(send(c.port, "GET", "/events?limit=200", null));
            boolean found = false;
            for (JsonNode e : events) {
                if ("AUDIT".equals(e.get("type").asText())
                        && "support_agent".equals(e.get("attributes").path("actor").asText())) found = true;
            }
            assertTrue(found, "X-Actor header threads through as the audit actor");
        }
    }

    @Test
    void eventRoutesAreAppendOnly(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // /events/{id} is a GET-only route → a mutation method is rejected (immutability guard).
            assertEquals(405, send(c.port, "DELETE", "/events/whatever", null).statusCode());
            assertEquals(405, send(c.port, "PUT", "/events/whatever", null).statusCode());
        }
    }

    @Test
    void forbiddenRouteAttemptIsAudited(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(404, send(c.port, "DELETE", "/nonexistent-secret-route", null).statusCode());

            JsonNode events = json(send(c.port, "GET", "/events?limit=200", null));
            boolean denied = false;
            for (JsonNode e : events) {
                if ("ACCESS_DENIED".equals(e.get("type").asText())
                        && e.get("attributes").path("http_path").asText().contains("nonexistent-secret-route"))
                    denied = true;
            }
            assertTrue(denied, "a non-GET attempt at an unknown route is recorded as ACCESS_DENIED");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
