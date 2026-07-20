package com.gamma.intelligence.action;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.gamma.control.ControlApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@code runbook_operator} compound act tool ({@link RunbookActions}) against a
 * stub control plane. They prove a seeded runbook runs its steps in order — each through its own
 * audited route carrying {@code X-Agent-Session} — halts on the first failed step (nothing after it
 * runs), and never touches the control plane on a pre-flight error (unknown runbook / missing params).
 */
class RunbookActionsTest {

    private record Recorded(String method, String path, String agentSession) {}

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    /** When non-null, any request whose path contains this substring returns 500 (to test halt-on-failure). */
    private volatile String failPathContains;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        System.setProperty(ControlApi.LOCAL_BASE_URL_PROP, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        System.clearProperty(ControlApi.LOCAL_BASE_URL_PROP);
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(new Recorded(ex.getRequestMethod(), path, ex.getRequestHeaders().getFirst("X-Agent-Session")));
        int status = failPathContains != null && path.contains(failPathContains) ? 500 : 200;
        // component GET (the apply pre-check) would 404→create; none of the seeded runbooks apply, so
        // every route here is a plain POST that just needs a 2xx/5xx.
        ex.getResponseHeaders().set("ETag", "\"sha256:x\"");
        byte[] out = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static ToolCall run(String runbook, Map<String, Object> params, String session) {
        return new ToolCall("runbook_operator", Map.of("runbook", runbook, "params", params), new RunId(session));
    }

    @Test
    @SuppressWarnings("unchecked")
    void triageAndReplayRunsBothStepsInOrderWithTheAgentHeader() {
        ToolResult r = RunbookActions.execute(new ControlPlaneClient(),
                run("triage_and_replay",
                        Map.of("alertId", "alert-1", "pipeline", "orders_etl", "batchId", "B-9"), "sess-1"), "sess-1");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("success"));
        assertEquals(2, v.get("completed"));
        assertEquals(2, v.get("total"));

        assertEquals(2, requests.size(), "both steps hit the control plane");
        assertEquals("/objects/alert-1/ack", requests.get(0).path(), "step 1 acks the alert first");
        assertEquals("/runs/orders_etl/reprocess", requests.get(1).path(), "step 2 replays the batch");
        assertTrue(requests.stream().allMatch(rq -> "sess-1".equals(rq.agentSession())),
                "every step carries X-Agent-Session");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedFirstStepHaltsTheRunbookAndNoLaterStepRuns() {
        failPathContains = "/ack"; // fail the first step (alert_ack)
        ToolResult r = RunbookActions.execute(new ControlPlaneClient(),
                run("triage_and_replay",
                        Map.of("alertId", "alert-2", "pipeline", "p", "batchId", "B-1"), "sess-2"), "sess-2");
        // Execution itself is ok=true; the runbook OUTCOME is success=false.
        assertTrue(r.ok(), () -> "runbook execution should not itself error: " + r.error());
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(false, v.get("success"));
        assertEquals(0, v.get("completed"));
        assertEquals(1, v.get("haltedAtStep"));

        assertEquals(1, requests.size(), "the reprocess step must NOT run after ack failed");
        assertEquals("/objects/alert-2/ack", requests.get(0).path());
        List<Map<String, Object>> steps = (List<Map<String, Object>>) v.get("steps");
        assertEquals(1, steps.size());
        assertEquals(false, steps.get(0).get("ok"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollbackAndRerunChainsAComponentActAndAnOperationalAct() {
        ToolResult r = RunbookActions.execute(new ControlPlaneClient(),
                run("rollback_and_rerun",
                        Map.of("type", "expectation", "id", "amt-nonneg", "version", 3, "job", "nightly"), "sess-3"), "sess-3");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("success"));
        assertEquals(2, requests.size());
        assertEquals("/components/expectation/amt-nonneg/versions/3/restore", requests.get(0).path());
        assertEquals("/jobs/nightly/trigger", requests.get(1).path());
    }

    @Test
    void unknownRunbookIsAnErrorAndNeverReachesTheControlPlane() {
        ToolResult r = RunbookActions.execute(new ControlPlaneClient(),
                run("no_such_runbook", Map.of(), "s"), "s");
        assertFalse(r.ok());
        assertTrue(r.error().contains("unknown runbook"), () -> "error: " + r.error());
        assertTrue(requests.isEmpty());
    }

    @Test
    void missingParamsAreCaughtBeforeAnyStepRuns() {
        ToolResult r = RunbookActions.execute(new ControlPlaneClient(),
                run("triage_and_replay", Map.of("alertId", "a"), "s"), "s"); // pipeline + batchId missing
        assertFalse(r.ok());
        assertTrue(r.error().contains("requires params"), () -> "error: " + r.error());
        assertTrue(r.error().contains("pipeline"));
        assertTrue(requests.isEmpty(), "a runbook missing params must never mutate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewShowsTheFullResolvedPlanWithoutMutating() {
        Map<String, Object> p = RunbookActions.preview(
                run("reschedule_and_trigger", Map.of("job", "sync", "cron", "0 * * * * *"), "s"));
        assertEquals("run-runbook", p.get("action"));
        assertEquals("reschedule_and_trigger", p.get("runbook"));
        List<Map<String, Object>> plan = (List<Map<String, Object>>) p.get("plan");
        assertEquals(2, plan.size());
        assertEquals("schedule_apply", plan.get(0).get("tool"));
        assertEquals("job_run", plan.get(1).get("tool"));
        assertEquals(true, plan.get(0).get("mutating"));
        @SuppressWarnings("unchecked")
        Map<String, Object> step1Args = (Map<String, Object>) plan.get(0).get("args");
        assertEquals("0 * * * * *", step1Args.get("cron"));
        assertTrue(requests.isEmpty(), "preview is read-only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewFlagsMissingParamsWithoutMutating() {
        Map<String, Object> p = RunbookActions.preview(run("triage_and_replay", Map.of("alertId", "a"), "s"));
        List<String> missing = (List<String>) p.get("missingParams");
        assertTrue(missing.contains("pipeline"));
        assertTrue(missing.contains("batchId"));
        assertTrue(requests.isEmpty());
    }
}
