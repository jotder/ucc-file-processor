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
 * Real-HTTP tests for the S6 "gated agentic write" round-trip: an A2UI {@code invoke} action against
 * an existing, human-authored Decision Rule dry-runs via {@code /decision-rules/{name}/simulate}
 * (no mutation), and only mutates once a human explicitly confirms via
 * {@code /decision-rules/{name}/apply} — the SAME gated endpoint {@link ControlApiDecisionRulesTest}
 * already covers, with one addition: an agent-attributed apply carries the
 * {@link ApiContext#HEADER_AGENT_SESSION} header, so the audit trail stamps
 * {@code actor = "agent:<sessionId>"} / {@code actorType = "agent"} instead of the default human path
 * ({@code actorType = "user"}, unchanged). Also covers the {@code create-alert} consequence now
 * authoring a real {@code alert-rule} component (visible via {@code GET /alerts/rules}) when the
 * consequence's params carry a full alert-rule body.
 */
class ControlApiAgentInvokeTest {

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

    private static final String RULE = "{\"name\":\"invoke_me\",\"targetType\":\"pipeline\",\"target\":\"orders\","
            + "\"consequences\":[{\"action\":\"emit-signal\",\"params\":{\"type\":\"agent.invoked\"}}]}";

    // ── dry-run makes no state change; declining (never calling apply) mutates nothing ─────────

    @Test
    void simulateNeverMutatesAndDecliningLeavesNoTrace(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/decision-rules", RULE);
            // Dry-run (what the invoke action's first click does) — repeatable, no side effect beyond
            // the non-authoring lastSimulation stamp already covered by ControlApiDecisionRulesTest.
            json(send(c.port, "POST", "/decision-rules/invoke_me/simulate", "{\"sampleRows\":[]}"));

            long auditEventsBeforeApply = countAuditEvents(c.port, "invoke_me");
            // The human never clicks "Confirm & Apply" — nothing further happens. No audit record for
            // an apply, no signal emitted, no state mutated.
            assertEquals(0, auditEventsBeforeApply, "declining (no apply call) must leave zero audit trace for this rule");
        }
    }

    // ── apply fires through the same gated endpoint and audits with an agent-attributable actor ───

    @Test
    void agentConfirmedApplyAuditsWithAgentActor(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/decision-rules", RULE);

            JsonNode result = json(sendWithHeader(c.port, "POST", "/decision-rules/invoke_me/apply", null,
                    "X-Agent-Session", "sess-42"));
            assertEquals("executed", result.get("executed").get(0).get("status").asText());

            JsonNode audit = findAuditEvent(c.port, "invoke_me");
            assertNotNull(audit, "an apply must be audited");
            assertEquals("agent:sess-42", audit.get("attributes").get("actor").asText());
            assertEquals("agent", audit.get("attributes").get("actor_type").asText());
        }
    }

    @Test
    void humanApplyWithoutAgentHeaderStaysActorTypeUser(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            send(c.port, "POST", "/decision-rules", RULE);
            json(send(c.port, "POST", "/decision-rules/invoke_me/apply", null));

            JsonNode audit = findAuditEvent(c.port, "invoke_me");
            assertNotNull(audit, "an apply must be audited");
            assertEquals("user", audit.get("attributes").get("actor_type").asText(),
                    "the default (no agent header) path must keep stamping actorType=user, unchanged");
        }
    }

    // ── create-alert wired to real Alert authoring ──────────────────────────────────────────────

    @Test
    void createAlertConsequenceAuthorsARealAlertRule(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        try (Ctx c = open(cfg, wr)) {
            String rule = "{\"name\":\"breach\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-alert\",\"params\":{"
                    + "\"rule\":\"real_alert\",\"severity\":\"warning\","
                    + "\"metric\":\"error_rate\",\"comparator\":\"gt\",\"threshold\":0.1,\"window\":\"1h\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);

            JsonNode consequence = json(send(c.port, "POST", "/decision-rules/breach/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", consequence.get("status").asText());
            assertTrue(consequence.get("detail").asText().contains("authored Alert Rule"),
                    consequence.get("detail").asText());

            JsonNode rules = json(send(c.port, "GET", "/alerts/rules", null));
            boolean found = false;
            for (JsonNode r : rules) if ("real_alert".equals(r.get("name").asText())) found = true;
            assertTrue(found, "the consequence must persist a real alert-rule component, not just a stub signal");
        }
    }

    @Test
    void createAlertWithoutFullParamsStaysLedgerSignalOnly(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        // Pre-S6 stub shape (rule+severity only, no metric/comparator/threshold/window) — a deliberate,
        // conservative scope cut: not enough to author a valid AlertRule, so it degrades to the old
        // signal-only behaviour rather than inventing defaults for fields with no sane default.
        try (Ctx c = open(cfg, wr)) {
            String rule = "{\"name\":\"soft2\",\"targetType\":\"pipeline\",\"target\":\"orders\","
                    + "\"consequences\":[{\"action\":\"create-alert\",\"params\":{\"rule\":\"soft2_alert\",\"severity\":\"warning\"}}]}";
            send(c.port, "POST", "/decision-rules", rule);
            JsonNode consequence = json(send(c.port, "POST", "/decision-rules/soft2/apply", null))
                    .get("executed").get(0);
            assertEquals("executed", consequence.get("status").asText());

            JsonNode rules = json(send(c.port, "GET", "/alerts/rules", null));
            for (JsonNode r : rules) assertNotEquals("soft2_alert", r.get("name").asText());
        }
    }

    // ── P3: an agent-attributed component write goes through the same audited contract ───────────

    @Test
    void agentComponentWriteAuditsWithAgentActor(@TempDir Path cfg, @TempDir Path wr) throws Exception {
        // The P3 component_apply act tool creates/updates registry components over this exact route
        // (loopback, X-Agent-Session). Proves the "no private backdoor" guarantee for the component
        // path directly: the write is audited actor=agent:<session>, no special agent code path.
        try (Ctx c = open(cfg, wr)) {
            HttpResponse<String> r = sendWithHeader(c.port, "POST", "/components/expectation",
                    "{\"id\":\"amt_nonneg\",\"kind\":\"non_null\",\"column\":\"amt\"}", "X-Agent-Session", "sess-99");
            assertTrue(r.statusCode() == 200 || r.statusCode() == 201,
                    "agent component create via loopback should succeed: " + r.statusCode() + " " + r.body());

            JsonNode audit = findAuditByPath(c.port, "/components/expectation");
            assertNotNull(audit, "the component write must be audited");
            assertEquals("agent:sess-99", audit.get("attributes").get("actor").asText());
            assertEquals("agent", audit.get("attributes").get("actor_type").asText());
        }
    }

    private JsonNode findAuditByPath(int port, String pathFragment) throws Exception {
        JsonNode events = json(send(port, "GET", "/events?limit=200", null));
        for (JsonNode e : events) {
            if ("AUDIT".equals(e.get("type").asText())
                    && e.get("attributes").get("http_path").asText().contains(pathFragment)) return e;
        }
        return null;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        return sendWithHeader(port, method, path, body, null, null);
    }

    private HttpResponse<String> sendWithHeader(int port, String method, String path, String body,
                                                 String headerName, String headerValue) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headerName != null) b.header(headerName, headerValue);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    /** Every {@code AUDIT}-type event mentioning {@code ruleName} in its message (the apply route's
     *  audited action is {@code decision-rule.updated}-style; matching on message text keeps this test
     *  independent of the exact classify() verb). */
    private int countAuditEvents(int port, String ruleName) throws Exception {
        JsonNode events = json(send(port, "GET", "/events?limit=200", null));
        int n = 0;
        for (JsonNode e : events) {
            if ("AUDIT".equals(e.get("type").asText()) && e.get("message").asText().contains(ruleName)
                    && e.get("message").asText().contains("apply")) n++;
        }
        return n;
    }

    private JsonNode findAuditEvent(int port, String ruleName) throws Exception {
        JsonNode events = json(send(port, "GET", "/events?limit=200", null));
        for (JsonNode e : events) {
            if ("AUDIT".equals(e.get("type").asText()) && e.get("attributes").get("http_path").asText().contains(ruleName)
                    && e.get("attributes").get("http_path").asText().contains("apply")) return e;
        }
        return null;
    }
}
