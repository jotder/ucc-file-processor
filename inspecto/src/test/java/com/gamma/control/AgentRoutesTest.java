package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.intelligence.AgentAnswerSink;
import com.gamma.intelligence.AgentAskRequest;
import com.gamma.intelligence.AgentAskResult;
import com.gamma.intelligence.AgentSessionRequest;
import com.gamma.intelligence.AgentSessionResult;
import com.gamma.intelligence.spi.IntelligenceAgent;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests for the AGT-5 (P0) {@code /agent/*} routes over real HTTP, against a fake {@link IntelligenceAgent}. */
class AgentRoutesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, IntelligenceAgent agent) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        if (agent != null) svc.registerIntelligenceAgent(agent);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void agentRoutesReturn503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions", "{}");
            assertEquals(503, r.statusCode());
        }
    }

    @Test
    void openSessionThenAskRoundTrips(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"page\":{\"pageId\":\"overview\"}}");
            assertEquals(200, opened.statusCode());
            JsonNode openedBody = JSON.readTree(opened.body());
            String sessionId = openedBody.get("sessionId").asText();
            assertFalse(sessionId.isBlank());

            HttpResponse<String> asked = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask", "{\"question\":\"How does ingestion work?\"}");
            assertEquals(200, asked.statusCode());
            JsonNode askedBody = JSON.readTree(asked.body());
            assertEquals("TEXT", askedBody.get("kind").asText());
            assertTrue(askedBody.get("text").asText().contains("How does ingestion work?"));
        }
    }

    @Test
    void askOnAnUnknownSessionIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST",
                    "/agent/sessions/does-not-exist/ask", "{\"question\":\"hi\"}");
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void askStreamRoundTripsAsServerSentEvents(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();

            HttpResponse<String> streamed = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask/stream", "{\"question\":\"stream this\"}");
            assertEquals(200, streamed.statusCode());
            assertEquals("text/event-stream", streamed.headers().firstValue("Content-Type").orElse(null));
            assertTrue(streamed.body().contains("event: complete"));
            assertTrue(streamed.body().contains("echo: stream this"));
        }
    }

    @Test
    void askStreamEmitsAnArtifactFrameBeforeComplete(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();

            HttpResponse<String> streamed = send(ctx.port(), "POST",
                    "/agent/sessions/" + sessionId + "/ask/stream", "{\"question\":\"stream this\"}");
            assertEquals(200, streamed.statusCode());
            String body = streamed.body();
            int artifactIdx = body.indexOf("event: artifact");
            int completeIdx = body.indexOf("event: complete");
            assertTrue(artifactIdx >= 0, "expected an event: artifact frame");
            assertTrue(artifactIdx < completeIdx, "artifact frame must precede the complete frame");

            // Extract the data: line belonging to the artifact frame and parse it (Map.of()'s
            // iteration order is unspecified/randomized per JVM run, so compare structurally).
            String afterArtifact = body.substring(artifactIdx);
            String dataPrefix = "data: ";
            int dataIdx = afterArtifact.indexOf(dataPrefix);
            String artifactJson = afterArtifact.substring(dataIdx + dataPrefix.length(),
                    afterArtifact.indexOf('\n', dataIdx));
            JsonNode artifactNode = JSON.readTree(artifactJson);
            assertEquals("chart", artifactNode.get("kind").asText());
            assertTrue(artifactNode.get("config").isObject());
        }
    }

    @Test
    void askStreamOnAnUnknownSessionIsAnErrorEventNotA404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST",
                    "/agent/sessions/does-not-exist/ask/stream", "{\"question\":\"hi\"}");
            assertEquals(200, r.statusCode()); // headers are already committed by the time the error is known
            assertTrue(r.body().contains("event: error"));
        }
    }

    @Test
    void askWithoutAQuestionIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> opened = send(ctx.port(), "POST", "/agent/sessions", "{}");
            String sessionId = JSON.readTree(opened.body()).get("sessionId").asText();
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions/" + sessionId + "/ask", "{}");
            assertEquals(400, r.statusCode());
        }
    }

    @Test
    void openSessionPassesAGoalKindThroughToTheAgent(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"goalKind\":\"INVESTIGATION\"}");
            assertEquals(200, r.statusCode());
            assertEquals("INVESTIGATION", agent.lastGoalKind);
        }
    }

    @Test
    void openSessionWithAnUnknownGoalKindIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/sessions",
                    "{\"role\":\"analyst\",\"goalKind\":\"NOT_A_KIND\"}");
            assertEquals(400, r.statusCode());
        }
    }

    @Test
    void casesRouteIs503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/cases", null).statusCode());
            assertEquals(503, send(ctx.port(), "GET", "/agent/cases/case-1", null).statusCode());
        }
    }

    @Test
    void recentCasesReturnsTheSeededCasesNewestFirst(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent(Map.of(
                "case-1", Map.of("id", "case-1", "outcome", "open"),
                "case-2", Map.of("id", "case-2", "outcome", "resolved"))))) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases", null);
            assertEquals(200, r.statusCode());
            JsonNode cases = JSON.readTree(r.body()).get("cases");
            assertEquals(2, cases.size());
        }
    }

    @Test
    void caseByIdReturnsTheMatchingCase(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent(Map.of(
                "case-1", Map.of("id", "case-1", "outcome", "open"))))) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases/case-1", null);
            assertEquals(200, r.statusCode());
            assertEquals("open", JSON.readTree(r.body()).get("outcome").asText());
        }
    }

    @Test
    void caseByIdOnAnUnknownIdIs404(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/cases/does-not-exist", null);
            assertEquals(404, r.statusCode());
        }
    }

    // --- AGT-5 P3: approvals inbox routes ---------------------------------------------------------

    @Test
    void approvalRoutesAre503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/approvals", null).statusCode());
            assertEquals(503, send(ctx.port(), "GET", "/agent/approvals/appr-1", null).statusCode());
            assertEquals(503, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"approve\"}").statusCode());
        }
    }

    @Test
    void recentApprovalsReturnsSeededEntries(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/approvals", null);
            assertEquals(200, r.statusCode());
            JsonNode approvals = JSON.readTree(r.body()).get("approvals");
            assertEquals(1, approvals.size());
            assertEquals("component_apply", approvals.get(0).get("tool").asText());
        }
    }

    @Test
    void approvalByIdReturnsTheMatchOr404(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> ok = send(ctx.port(), "GET", "/agent/approvals/appr-1", null);
            assertEquals(200, ok.statusCode());
            assertEquals("PENDING", JSON.readTree(ok.body()).get("status").asText());
            assertEquals(404, send(ctx.port(), "GET", "/agent/approvals/nope", null).statusCode());
        }
    }

    @Test
    void decisionApprovesAPendingApproval(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"approve\",\"decidedBy\":\"alice\"}");
            assertEquals(200, r.statusCode());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("APPROVED", body.get("status").asText());
            assertEquals("alice", body.get("decidedBy").asText());
        }
    }

    @Test
    void decisionOnAnUnknownOrDecidedApprovalIs404(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "APPROVED"); // already decided
        try (Ctx ctx = open(dir, agent)) {
            assertEquals(404, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"approve\"}").statusCode());
            assertEquals(404, send(ctx.port(), "POST", "/agent/approvals/nope/decision",
                    "{\"decision\":\"approve\"}").statusCode());
        }
    }

    @Test
    void decisionWithAMissingOrUnrecognizedVerbIs400(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedApproval("appr-1", "component_apply", "PENDING");
        try (Ctx ctx = open(dir, agent)) {
            assertEquals(400, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision",
                    "{\"decision\":\"maybe\"}").statusCode());
            assertEquals(400, send(ctx.port(), "POST", "/agent/approvals/appr-1/decision", "{}").statusCode());
        }
    }

    // --- AGT-5 P4: autonomy policy routes ---------------------------------------------------------

    @Test
    void policyRoutesAre503WhenNoIntelligenceModuleIsPresent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/policy", null).statusCode());
            assertEquals(503, send(ctx.port(), "PUT", "/agent/policy", "{}").statusCode());
            assertEquals(503, send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":true}").statusCode());
        }
    }

    @Test
    void getPolicyReturnsTheCurrentView(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/policy", null);
            assertEquals(200, r.statusCode());
            assertFalse(JSON.readTree(r.body()).get("killSwitch").asBoolean());
        }
    }

    @Test
    void putPolicyReplacesAndAttributesTheActor(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> r = send(ctx.port(), "PUT", "/agent/policy",
                    "{\"classes\":{\"batch_rerun\":{\"mode\":\"auto\",\"maxPerHour\":3}}}");
            assertEquals(200, r.statusCode());
            JsonNode body = JSON.readTree(r.body());
            assertEquals("auto", body.get("classes").get("batch_rerun").get("mode").asText());
            // No X-Agent-Session header → attributed to the calling human actor, not "agent:*".
            assertFalse(body.get("updatedBy").asText().startsWith("agent:"));
        }
    }

    @Test
    void killSwitchEngagesAndDisengages(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            HttpResponse<String> on = send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":true}");
            assertEquals(200, on.statusCode());
            assertTrue(JSON.readTree(on.body()).get("killSwitch").asBoolean());

            HttpResponse<String> off = send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":false}");
            assertFalse(JSON.readTree(off.body()).get("killSwitch").asBoolean());
        }
    }

    @Test
    void killSwitchWithoutEngagedIs400(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, new FakeIntelligenceAgent())) {
            assertEquals(400, send(ctx.port(), "POST", "/agent/policy/kill-switch", "{}").statusCode());
            assertEquals(400, send(ctx.port(), "POST", "/agent/policy/kill-switch",
                    "{\"engaged\":\"maybe\"}").statusCode());
        }
    }

    // --- AGT-5 P5: case-similarity recall route ---------------------------------------------------

    @Test
    void similarCasesRouteReturnsNeighboursOr404(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent(Map.of("case-1", Map.of("id", "case-1")));
        agent.seedSimilar("case-1", List.of(Map.of("id", "case-2", "similarity", 0.5)));
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> ok = send(ctx.port(), "GET", "/agent/cases/case-1/similar", null);
            assertEquals(200, ok.statusCode());
            JsonNode similar = JSON.readTree(ok.body()).get("similar");
            assertEquals(1, similar.size());
            assertEquals("case-2", similar.get(0).get("id").asText());
            // The greedy /agent/cases/(.+) must not shadow /similar (registration-order match).
            assertEquals(404, send(ctx.port(), "GET", "/agent/cases/nope/similar", null).statusCode());
        }
    }

    // --- AGT-5 P5: Case feedback routes -----------------------------------------------------------

    @Test
    void feedbackPostValidatesAndRecords(@TempDir Path dir) throws Exception {
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent(Map.of("case-1", Map.of("id", "case-1")));
        try (Ctx ctx = open(dir, agent)) {
            // Missing rating → 400.
            assertEquals(400, send(ctx.port(), "POST", "/agent/cases/case-1/feedback", "{}").statusCode());
            // Unknown case → 404.
            assertEquals(404, send(ctx.port(), "POST", "/agent/cases/nope/feedback",
                    "{\"rating\":\"helpful\"}").statusCode());
            // Valid → 200 + stored view.
            HttpResponse<String> ok = send(ctx.port(), "POST", "/agent/cases/case-1/feedback",
                    "{\"rating\":\"helpful\",\"note\":\"good\"}");
            assertEquals(200, ok.statusCode());
            assertEquals("HELPFUL", JSON.readTree(ok.body()).get("rating").asText());
        }
    }

    @Test
    void feedbackListDegradesEmptyAnd503WhenModuleAbsent(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/feedback", null).statusCode());
        }
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent(Map.of("case-1", Map.of("id", "case-1")));
        try (Ctx ctx = open(dir, agent)) {
            send(ctx.port(), "POST", "/agent/cases/case-1/feedback", "{\"rating\":\"not_helpful\"}");
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/feedback", null);
            assertEquals(200, r.statusCode());
            assertEquals(1, JSON.readTree(r.body()).get("feedback").size());
        }
    }

    @Test
    void actionsRoutesDegradeTo503OnlyWhenModuleAbsentAndReturnSeededEntries(@TempDir Path dir) throws Exception {
        try (Ctx ctx = open(dir, null)) {
            assertEquals(503, send(ctx.port(), "GET", "/agent/actions", null).statusCode());
        }
        FakeIntelligenceAgent agent = new FakeIntelligenceAgent();
        agent.seedAction("act-1", "batch_rerun", "SUCCEEDED");
        try (Ctx ctx = open(dir, agent)) {
            HttpResponse<String> r = send(ctx.port(), "GET", "/agent/actions", null);
            assertEquals(200, r.statusCode());
            JsonNode actions = JSON.readTree(r.body()).get("actions");
            assertEquals(1, actions.size());
            assertEquals("batch_rerun", actions.get(0).get("actionClass").asText());
            assertEquals(200, send(ctx.port(), "GET", "/agent/actions/act-1", null).statusCode());
            assertEquals(404, send(ctx.port(), "GET", "/agent/actions/nope", null).statusCode());
        }
    }

    /** A deterministic in-memory agent — no eoiagent/model dependency needed in the core test tree. */
    private static final class FakeIntelligenceAgent implements IntelligenceAgent {
        // Stand-in for the eoiagent GoalKind enum (not on the core test classpath).
        private static final java.util.Set<String> KNOWN_GOAL_KINDS =
                java.util.Set.of("QA", "ANALYSIS", "SQL_GEN", "PIPELINE_AUTHOR", "INVESTIGATION", "OPERATIONAL_ACTION");

        private final Map<String, String> sessions = new ConcurrentHashMap<>();
        private final Map<String, Object> cases;
        // Insertion-ordered so recentApprovals is deterministic; entries mutate on decision.
        private final Map<String, Map<String, Object>> approvals = new java.util.LinkedHashMap<>();
        volatile String lastGoalKind;

        FakeIntelligenceAgent() { this(Map.of()); }
        FakeIntelligenceAgent(Map<String, Object> cases) { this.cases = cases; }

        /** Seed one approval view (mirrors the {@code Approval.toView()} shape the real agent emits). */
        void seedApproval(String id, String tool, String status) {
            Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("id", id);
            view.put("tool", tool);
            view.put("status", status);
            approvals.put(id, view);
        }

        // P4: a trivial in-memory policy the route tests exercise (echo-and-store, no real engine).
        private Map<String, Object> policy = new java.util.LinkedHashMap<>(
                Map.of("killSwitch", false, "classes", new java.util.LinkedHashMap<>()));

        @Override public String name() { return "fake-intelligence"; }
        @Override public void init(CollectorService service) {}

        @Override
        public java.util.Optional<Map<String, Object>> autonomyPolicy() {
            return java.util.Optional.of(new java.util.LinkedHashMap<>(policy));
        }

        @Override
        public java.util.Optional<Map<String, Object>> updateAutonomyPolicy(Map<String, Object> body, String by) {
            Map<String, Object> next = new java.util.LinkedHashMap<>(body == null ? Map.of() : body);
            next.putIfAbsent("killSwitch", false);
            next.put("updatedBy", by);
            this.policy = next;
            return java.util.Optional.of(new java.util.LinkedHashMap<>(policy));
        }

        @Override
        public java.util.Optional<Map<String, Object>> setAutonomyKillSwitch(boolean engaged, String by) {
            policy.put("killSwitch", engaged);
            policy.put("updatedBy", by);
            return java.util.Optional.of(new java.util.LinkedHashMap<>(policy));
        }

        // P5: Case feedback the /agent/cases/{id}/feedback + /agent/feedback routes exercise.
        private final List<Map<String, Object>> feedback = new java.util.ArrayList<>();

        @Override
        public java.util.Optional<Map<String, Object>> recordCaseFeedback(String caseId, Map<String, Object> body, String by) {
            if (!cases.containsKey(caseId)) return java.util.Optional.empty(); // unknown case → 404
            String rating = String.valueOf(body.get("rating"));
            if (!"helpful".equalsIgnoreCase(rating) && !"not_helpful".equalsIgnoreCase(rating)) {
                throw new IllegalArgumentException("bad rating"); // → route maps to 400
            }
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("id", "fb-" + feedback.size());
            v.put("caseId", caseId);
            v.put("rating", rating.toUpperCase(java.util.Locale.ROOT));
            v.put("submittedBy", by);
            feedback.add(v);
            return java.util.Optional.of(v);
        }

        @Override
        public List<Map<String, Object>> recentCaseFeedback(int limit) {
            return List.copyOf(feedback);
        }

        // P4 slice 2: the autonomy ledger the /agent/actions routes read.
        private final Map<String, Map<String, Object>> actions = new java.util.LinkedHashMap<>();

        void seedAction(String id, String actionClass, String status) {
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("id", id);
            v.put("actionClass", actionClass);
            v.put("status", status);
            actions.put(id, v);
        }

        @Override
        public List<Map<String, Object>> recentAutonomousActions(int limit) {
            return List.copyOf(actions.values());
        }

        @Override
        public java.util.Optional<Map<String, Object>> autonomousActionById(String id) {
            return java.util.Optional.ofNullable(actions.get(id));
        }

        @Override
        public List<Map<String, Object>> recentApprovals(int limit) {
            return List.copyOf(approvals.values());
        }

        @Override
        public java.util.Optional<Map<String, Object>> approvalById(String id) {
            return java.util.Optional.ofNullable(approvals.get(id));
        }

        @Override
        public java.util.Optional<Map<String, Object>> decideApproval(String id, boolean approve, String decidedBy) {
            Map<String, Object> a = approvals.get(id);
            if (a == null || !"PENDING".equals(a.get("status"))) return java.util.Optional.empty();
            Map<String, Object> updated = new java.util.LinkedHashMap<>(a);
            updated.put("status", approve ? "APPROVED" : "DENIED");
            updated.put("decidedBy", decidedBy);
            approvals.put(id, updated);
            return java.util.Optional.of(updated);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> recentCases(int limit) {
            return cases.values().stream().map(v -> (Map<String, Object>) v).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public java.util.Optional<Map<String, Object>> caseById(String id) {
            return java.util.Optional.ofNullable((Map<String, Object>) cases.get(id));
        }

        // P5: seeded similarity neighbours the /agent/cases/{id}/similar route reads.
        private final Map<String, List<Map<String, Object>>> similar = new java.util.LinkedHashMap<>();

        void seedSimilar(String caseId, List<Map<String, Object>> neighbours) {
            similar.put(caseId, neighbours);
        }

        @Override
        public List<Map<String, Object>> similarCases(String id, int k) {
            return similar.getOrDefault(id, List.of());
        }

        @Override
        public AgentSessionResult openSession(AgentSessionRequest request) {
            lastGoalKind = request.goalKind();
            if (lastGoalKind != null && !KNOWN_GOAL_KINDS.contains(lastGoalKind)) {
                throw new IllegalArgumentException("unknown goalKind: '" + lastGoalKind + "'"); // → route maps to 400
            }
            String id = UUID.randomUUID().toString();
            sessions.put(id, request.role() == null ? "" : request.role()); // ConcurrentHashMap forbids null values
            return new AgentSessionResult(id, Instant.now().toString());
        }

        @Override
        public AgentAskResult ask(String sessionId, AgentAskRequest request) {
            if (!sessions.containsKey(sessionId)) {
                throw new IllegalArgumentException("unknown session: '" + sessionId + "'");
            }
            return new AgentAskResult("TEXT", "echo: " + request.question(), List.of(), null, null);
        }

        @Override
        public void askStream(String sessionId, AgentAskRequest request, AgentAnswerSink sink) {
            try {
                AgentAskResult result = ask(sessionId, request);
                sink.onArtifact(Map.of("kind", "chart", "config", Map.of()));
                sink.onComplete(result);
            } catch (IllegalArgumentException e) {
                sink.onError(e.getMessage());
            }
        }
    }
}
