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
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for the Decision Rule routes ({@code /decision-rules*}): authored CRUD over the
 * {@code decision-rule} component store with its fail-closed gates, and — the point of this class —
 * {@code simulate} now evaluating the rule's {@code when} condition tree over a caller-supplied
 * {@code sampleRows} batch (via {@link com.gamma.query.ConditionTree}) and returning real
 * {@code matched}/{@code total} counts, with a no-sample request yielding {@code 0/0}.
 */
class ControlApiDecisionRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path toon = TestConfigs.csv(cfg, PipelineConfigBatchTest.miniSchema()).write();
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    // A rule whose `when` keeps only rows with cost > 5000. One consequence (required, non-empty).
    private static final String HI_COST_RULE = "{\"name\":\"quarantine_high_cost\",\"targetType\":\"pipeline\","
            + "\"target\":\"orders\",\"consequences\":[{\"action\":\"quarantine\",\"destination\":\"too costly\"}],"
            + "\"when\":{\"kind\":\"group\",\"op\":\"AND\",\"items\":["
            + "{\"kind\":\"condition\",\"field\":\"cost\",\"operator\":\">\",\"value\":\"5000\"}]}}";

    // ── CRUD + gates ──────────────────────────────────────────────────────────────

    @Test
    void crudRoundTripAndGates(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            JsonNode created = json(send(c.port, "POST", "/decision-rules", HI_COST_RULE));
            assertEquals("quarantine_high_cost", created.get("name").asText());
            assertTrue(created.get("lastSimulation").isNull());

            JsonNode list = json(send(c.port, "GET", "/decision-rules", null));
            assertTrue(list.isArray() && list.size() == 1);

            // duplicate create → 409
            assertEquals(409, send(c.port, "POST", "/decision-rules", HI_COST_RULE).statusCode());
            // missing name → 422
            assertEquals(422, send(c.port, "POST", "/decision-rules",
                    "{\"consequences\":[{\"action\":\"drop\"}]}").statusCode());
            // no consequence → 422
            assertEquals(422, send(c.port, "POST", "/decision-rules",
                    "{\"name\":\"empty\",\"consequences\":[]}").statusCode());

            // update, then update-unknown → 404
            JsonNode updated = json(send(c.port, "PUT", "/decision-rules/quarantine_high_cost",
                    "{\"consequences\":[{\"action\":\"drop\"}],\"description\":\"edited\"}"));
            assertEquals("edited", updated.get("description").asText());
            assertEquals(404, send(c.port, "PUT", "/decision-rules/nope",
                    "{\"consequences\":[{\"action\":\"drop\"}]}").statusCode());

            // delete + delete-unknown
            assertEquals("quarantine_high_cost",
                    json(send(c.port, "DELETE", "/decision-rules/quarantine_high_cost", null)).get("deleted").asText());
            assertEquals(404, send(c.port, "DELETE", "/decision-rules/quarantine_high_cost", null).statusCode());
        }
    }

    @Test
    void unsafeNameRejected(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            // A valid body but a name unusable as a component id (ComponentStore.validId) → 422, not a
            // 500 from the IllegalArgumentException reaching the generic handler.
            assertEquals(422, send(c.port, "POST", "/decision-rules",
                    "{\"name\":\"../evil\",\"consequences\":[{\"action\":\"drop\"}]}").statusCode());
            // Same for a path-supplied name on update/delete ('..' passes the route's [^/]+ segment).
            assertEquals(422, send(c.port, "PUT", "/decision-rules/..evil",
                    "{\"consequences\":[{\"action\":\"drop\"}]}").statusCode());
            assertEquals(422, send(c.port, "DELETE", "/decision-rules/..evil", null).statusCode());
        }
    }

    // ── simulate: real condition-tree evaluation over a sample ─────────────────────

    @Test
    void simulateEvaluatesWhenOverSampleRows(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/decision-rules", HI_COST_RULE);

            String sample = "{\"sampleRows\":[{\"cost\":9200},{\"cost\":50},{\"cost\":7000}]}";
            JsonNode sim = json(send(c.port, "POST", "/decision-rules/quarantine_high_cost/simulate", sample))
                    .get("lastSimulation");
            assertEquals(2, sim.get("matched").asInt(), "cost > 5000 ⇒ the 9200 and 7000 rows");
            assertEquals(3, sim.get("total").asInt());
            assertTrue(sim.get("checkedAt").asLong() > 0);
        }
    }

    @Test
    void simulateWithNoSampleIsZeroOverZero(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/decision-rules", HI_COST_RULE);
            JsonNode sim = json(send(c.port, "POST", "/decision-rules/quarantine_high_cost/simulate", null))
                    .get("lastSimulation");
            assertEquals(0, sim.get("matched").asInt());
            assertEquals(0, sim.get("total").asInt());
        }
    }

    @Test
    void simulateUnknownRuleIs404(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            assertEquals(404, send(c.port, "POST", "/decision-rules/ghost/simulate",
                    "{\"sampleRows\":[]}").statusCode());
        }
    }

    // ── apply: create-alert consequence → Incident promotion (critical/error only) ──

    @Test
    void applyCreateAlertPromotesCriticalToIncidentDeduped(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String rule = "{\"name\":\"cost_breach\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-alert\","
                    + "\"params\":{\"rule\":\"cost_alert\",\"severity\":\"critical\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);

            JsonNode consequence = json(send(c.port, "POST", "/decision-rules/cost_breach/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", consequence.get("status").asText());
            assertTrue(consequence.get("detail").asText().contains("Incident"), consequence.get("detail").asText());

            assertEquals(1, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "a critical create-alert consequence opens a managed Incident");

            // re-apply while the Incident is open → deduped, still one
            send(c.port, "POST", "/decision-rules/cost_breach/apply", null);
            assertEquals(1, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "one open Incident per rule — re-apply does not clone it");
        }
    }

    @Test
    void applyCreateAlertWarningStaysSignalOnly(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String rule = "{\"name\":\"soft\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-alert\","
                    + "\"params\":{\"rule\":\"soft_alert\",\"severity\":\"warning\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);
            json(send(c.port, "POST", "/decision-rules/soft/apply", null));
            assertEquals(0, json(send(c.port, "GET", "/objects?type=INCIDENT", null)).size(),
                    "a warning create-alert stays a ledger signal — no Incident");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

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
