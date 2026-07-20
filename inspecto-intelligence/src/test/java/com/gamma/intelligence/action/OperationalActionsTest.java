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
 * End-to-end tests for the P3 operational act tools ({@link OperationalActions}) driving the loopback
 * {@link ControlPlaneClient} against a stub control plane. They assert the exact wire contract the
 * "no private backdoor" guarantee rests on — the governed route each verb rides
 * ({@code /jobs/{name}/trigger}, {@code /runs/{pipeline}/reprocess}, {@code /objects/{id}/ack},
 * {@code /jobs/{name}/reschedule}), the request body, and the {@code X-Agent-Session} attribution
 * header on every write — plus the non-2xx and absent-control-plane failure paths. (The real
 * {@code actor=agent} audit on those routes is proven separately in the core module.)
 */
class OperationalActionsTest {

    private record Recorded(String method, String path, String agentSession, String body) {}

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int status = 200;

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
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String agent = ex.getRequestHeaders().getFirst("X-Agent-Session");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new Recorded(method, path, agent, body));
        byte[] out = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static ToolCall call(String tool, Map<String, Object> args, String session) {
        return new ToolCall(tool, args, new RunId(session));
    }

    @Test
    void jobRunPostsToTheTriggerRouteWithTheAgentHeader() {
        ToolResult r = OperationalActions.jobRun(new ControlPlaneClient(),
                call("job_run", Map.of("job", "nightly-rollup"), "sess-1"), "sess-1");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("triggered"));
        assertEquals("nightly-rollup", v.get("job"));
        assertEquals("agent:sess-1", v.get("actor"));

        assertEquals(1, requests.size());
        Recorded post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/jobs/nightly-rollup/trigger", post.path());
        assertEquals("sess-1", post.agentSession(), "every write carries X-Agent-Session");
    }

    @Test
    void jobRunForwardsParamsInTheBody() {
        ToolResult r = OperationalActions.jobRun(new ControlPlaneClient(),
                call("job_run", Map.of("job", "backfill", "params", Map.of("day", "2026-07-01")), "sess-2"), "sess-2");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        Recorded post = requests.get(0);
        assertTrue(post.body().contains("params"), "params object must be forwarded in the body");
        assertTrue(post.body().contains("2026-07-01"));
    }

    @Test
    void pipelineRerunPostsBatchIdToTheReprocessRoute() {
        ToolResult r = OperationalActions.pipelineRerun(new ControlPlaneClient(),
                call("pipeline_rerun", Map.of("pipeline", "orders_etl", "batchId", "B-42"), "sess-3"), "sess-3");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("reprocessed"));
        assertEquals("B-42", v.get("batchId"));

        Recorded post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/runs/orders_etl/reprocess", post.path());
        assertTrue(post.body().contains("B-42"), "reprocess body must carry the batchId");
        assertEquals("sess-3", post.agentSession());
    }

    @Test
    void alertAckPostsToTheObjectAckRoute() {
        ToolResult r = OperationalActions.alertAck(new ControlPlaneClient(),
                call("alert_ack", Map.of("id", "alert-9001"), "sess-4"), "sess-4");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("acknowledged"));
        assertEquals("alert-9001", v.get("id"));

        Recorded post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/objects/alert-9001/ack", post.path());
        assertEquals("sess-4", post.agentSession());
    }

    @Test
    void scheduleApplyPostsCronToTheRescheduleRoute() {
        ToolResult r = OperationalActions.scheduleApply(new ControlPlaneClient(),
                call("schedule_apply", Map.of("job", "hourly-sync", "cron", "0 0 * * * *"), "sess-5"), "sess-5");
        assertTrue(r.ok(), () -> "expected ok, got: " + r.error());
        @SuppressWarnings("unchecked")
        Map<String, Object> v = (Map<String, Object>) r.value();
        assertEquals(true, v.get("rescheduled"));
        assertEquals("0 0 * * * *", v.get("cron"));

        Recorded post = requests.get(0);
        assertEquals("POST", post.method());
        assertEquals("/jobs/hourly-sync/reschedule", post.path());
        assertTrue(post.body().contains("0 0 * * * *"), "reschedule body must carry the cron");
        assertEquals("sess-5", post.agentSession());
    }

    @Test
    void aNon2xxControlPlaneResponseIsAnHonestError() {
        status = 404;
        ToolResult r = OperationalActions.jobRun(new ControlPlaneClient(),
                call("job_run", Map.of("job", "ghost"), "sess-6"), "sess-6");
        assertFalse(r.ok());
        assertTrue(r.error().contains("404"), () -> "error: " + r.error());
    }

    @Test
    void anAbsentControlPlaneDegradesToAnHonestError() {
        System.clearProperty(ControlApi.LOCAL_BASE_URL_PROP);
        ToolResult r = OperationalActions.pipelineRerun(new ControlPlaneClient(),
                call("pipeline_rerun", Map.of("pipeline", "p", "batchId", "B1"), "sess-7"), "sess-7");
        assertFalse(r.ok());
        assertTrue(r.error().toLowerCase().contains("not reachable"), () -> "error: " + r.error());
    }

    @Test
    void missingRequiredArgsAreErrorsAndNeverReachTheControlPlane() {
        assertFalse(OperationalActions.jobRun(new ControlPlaneClient(),
                call("job_run", Map.of(), "s"), "s").ok());
        assertFalse(OperationalActions.pipelineRerun(new ControlPlaneClient(),
                call("pipeline_rerun", Map.of("pipeline", "p"), "s"), "s").ok(), "batchId required");
        assertFalse(OperationalActions.alertAck(new ControlPlaneClient(),
                call("alert_ack", Map.of(), "s"), "s").ok());
        assertFalse(OperationalActions.scheduleApply(new ControlPlaneClient(),
                call("schedule_apply", Map.of("job", "j"), "s"), "s").ok(), "cron required");
        assertTrue(requests.isEmpty(), "an invalid call must never reach the control plane");
    }

    @Test
    void previewSummarizesTheActionWithoutMutating() {
        // No CollectorService needed for the action summary itself; live-state fields degrade to false.
        Map<String, Object> job = OperationalActions.preview(null,
                call("job_run", Map.of("job", "nightly"), "s"));
        assertEquals("run-job", job.get("action"));
        assertEquals("nightly", job.get("target"));
        assertEquals(false, job.get("jobExists"), "no service → existence unknown, reported false");

        Map<String, Object> rerun = OperationalActions.preview(null,
                call("pipeline_rerun", Map.of("pipeline", "orders", "batchId", "B-7"), "s"));
        assertEquals("reprocess-batch", rerun.get("action"));
        assertEquals("B-7", rerun.get("batchId"));

        Map<String, Object> sched = OperationalActions.preview(null,
                call("schedule_apply", Map.of("job", "j", "cron", "* * * * * *"), "s"));
        assertEquals("reschedule-job", sched.get("action"));
        assertEquals("* * * * * *", sched.get("newCron"));

        assertTrue(requests.isEmpty(), "preview is read-only and never calls the control plane");
    }
}
