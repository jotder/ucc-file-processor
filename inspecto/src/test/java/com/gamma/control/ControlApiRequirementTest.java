package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for the Requirements intake engine ({@code /requirements*}, UI-6 + SEC-7(c)): the
 * submit → decide → deliver lifecycle, the fail-closed gates, and — the SEC-7(c) point — the triage
 * transitions enforced server-side on {@code canTriageRequirements} while submission stays open. The
 * capability tests force an {@link Authenticator} via {@link Authenticators#forTest} to stand in for the
 * Standard edition (same technique as {@link ControlApiAuthV1Test}); Personal has none.
 */
class ControlApiRequirementTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer triager} → can triage; {@code Bearer business} → authenticated, no capabilities. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer triager".equals(auth)) return Optional.of(new Subject("builder", Set.of("canTriageRequirements")));
        if ("Bearer business".equals(auth)) return Optional.of(new Subject("biz", Set.of()));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() { Authenticators.forTest(null); }

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            SourceService svc = new SourceService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── lifecycle + gates (Personal: no Authenticator) ─────────────────────────────

    @Test
    void submitDecideDeliverLifecycleAndGates(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String submit = "{\"id\":\"churn_kpi\",\"title\":\"Churn KPI\",\"kind\":\"kpi\",\"description\":\"monthly churn\"}";
            JsonNode created = json(send(c.port, "POST", "/requirements", submit));
            assertEquals("churn_kpi", created.get("id").asText());
            assertEquals("submitted", created.get("status").asText());
            assertFalse(created.has("name"), "the component name is surfaced as id, not leaked");

            assertEquals(1, json(send(c.port, "GET", "/requirements", null)).size());

            // gates: duplicate → 409, bad kind → 422, unknown decision → 404
            assertEquals(409, send(c.port, "POST", "/requirements", submit).statusCode());
            assertEquals(422, send(c.port, "POST", "/requirements",
                    "{\"id\":\"x\",\"title\":\"t\",\"kind\":\"bogus\"}").statusCode());
            assertEquals(404, send(c.port, "POST", "/requirements/nope/decision", "{\"accept\":true}").statusCode());

            // accept → deliver
            JsonNode accepted = json(send(c.port, "POST", "/requirements/churn_kpi/decision",
                    "{\"accept\":true,\"note\":\"good idea\"}"));
            assertEquals("accepted", accepted.get("status").asText());
            assertEquals("good idea", accepted.get("decisionNote").asText());

            // decide again → 409 (no longer submitted)
            assertEquals(409, send(c.port, "POST", "/requirements/churn_kpi/decision",
                    "{\"accept\":false}").statusCode());

            JsonNode delivered = json(send(c.port, "POST", "/requirements/churn_kpi/deliver",
                    "{\"note\":\"dashboard/churn\"}"));
            assertEquals("delivered", delivered.get("status").asText());
            assertEquals("dashboard/churn", delivered.get("deliveredNote").asText());
        }
    }

    @Test
    void deliverRequiresAccepted(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/requirements", "{\"id\":\"r1\",\"title\":\"t\",\"kind\":\"report\"}");
            // still submitted → cannot deliver
            assertEquals(409, send(c.port, "POST", "/requirements/r1/deliver", "{}").statusCode());
        }
    }

    // ── SEC-7(c): triage enforced on canTriageRequirements (Standard) ───────────────

    @Test
    void triageIsGatedButSubmissionIsOpen(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, wr)) {
            // a Business subject (no capabilities) may submit
            assertEquals(200, send(c.port, "POST", "/api/v1/requirements",
                    "{\"id\":\"r1\",\"title\":\"t\",\"kind\":\"kpi\"}", "Authorization", "Bearer business").statusCode());

            // …but may NOT decide → 403 PERMISSION_DENIED
            HttpResponse<String> denied = send(c.port, "POST", "/api/v1/requirements/r1/decision",
                    "{\"accept\":true}", "Authorization", "Bearer business");
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals("PERMISSION_DENIED", JSON.readTree(denied.body()).get("error").get("errorCode").asText());

            // a triager (canTriageRequirements) may decide
            assertEquals(200, send(c.port, "POST", "/api/v1/requirements/r1/decision",
                    "{\"accept\":true}", "Authorization", "Bearer triager").statusCode());
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
