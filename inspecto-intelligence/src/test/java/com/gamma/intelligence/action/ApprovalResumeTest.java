package com.gamma.intelligence.action;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AGT-5 P3 resume-after-restart: a durable {@link ApprovalStore} keeps pending approvals and
 * undelivered operator decisions across a process restart, and {@link AgentApprovals} as a
 * {@code DecisionStore} lets a re-issued identical call proceed under the persisted decision
 * without re-prompting the operator.
 */
class ApprovalResumeTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    private static Map<String, Object> args() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", "nightly-rollup");
        m.put("params", Map.of("date", "2026-07-20"));
        return m;
    }

    private static ApprovalRequest request(String tool, Map<String, Object> args, String runId) {
        ToolCall call = new ToolCall(tool, args, new RunId(runId));
        return new ApprovalRequest(call.run(), call, "Approve '" + tool + "'",
                new DryRunResult(false, "", Map.of()));
    }

    private static String awaitPendingId(AgentApprovals approvals) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<Map<String, Object>> p = approvals.recent(10).stream()
                    .filter(a -> "PENDING".equals(a.get("status"))).findFirst();
            if (p.isPresent()) return (String) p.get().get("id");
            Thread.sleep(10);
        }
        fail("no pending approval appeared");
        throw new AssertionError("unreachable");
    }

    @Test
    void aPendingApprovalDecidedAfterRestartResumesTheReissuedCall(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agent").resolve("approvals.jsonl");

        // Process 1: a run parks on the gate, raising a pending approval; then the JVM goes down
        // (the parked gate thread is gone), leaving the approval persisted and still PENDING.
        String id;
        {
            AgentApprovals before = new AgentApprovals(new ApprovalStore(file), call -> Map.of());
            Future<ApprovalDecision> gate = pool.submit(() -> before.decide(request("job_run", args(), "run-A")));
            id = awaitPendingId(before);
            gate.cancel(false); // stop referencing the run; the persisted approval stays PENDING on disk
        }

        // Process 2: a fresh instance loads the persisted PENDING approval — no waiting future here.
        AgentApprovals after = new AgentApprovals(new ApprovalStore(file), call -> Map.of());
        assertEquals("PENDING", after.byId(id).orElseThrow().get("status"));

        // Operator approves with no run waiting → held as a one-shot resume token.
        assertEquals("APPROVED", after.resolve(id, true, "operator").orElseThrow().get("status"));

        // The re-issued run (a new RunId, same tool+args) is admitted without re-prompting.
        assertEquals(ApprovalDecision.APPROVED,
                after.find(request("job_run", args(), "run-A-reissue")).orElseThrow());

        // One-shot: a second re-issue finds no token and would fall through to a fresh prompt.
        assertTrue(after.find(request("job_run", args(), "run-A-reissue-2")).isEmpty());
    }

    @Test
    void aLiveDeliveredDecisionIsNotReusableAsAResumeToken(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("approvals.jsonl");
        AgentApprovals approvals = new AgentApprovals(new ApprovalStore(file), call -> Map.of());
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("job_run", args(), "run-B")));
        String id = awaitPendingId(approvals);

        // Delivered live to the parked run — consumed, so it can never double as a resume token.
        approvals.resolve(id, true, "operator");
        assertEquals(ApprovalDecision.APPROVED, gate.get(2, TimeUnit.SECONDS));
        assertTrue(approvals.find(request("job_run", args(), "run-B-reissue")).isEmpty());
    }

    @Test
    void mismatchedArgumentsDoNotConsumeAToken(@TempDir Path dir) {
        Path file = dir.resolve("approvals.jsonl");
        ApprovalStore store = new ApprovalStore(file);
        AgentApprovals approvals = new AgentApprovals(store, call -> Map.of());
        store.add(new Approval("id-1", "job_run", "agent:run-C", "s", args(), Map.of(), java.time.Instant.now()));
        approvals.resolve("id-1", true, "operator"); // token held (no waiting run)

        Map<String, Object> other = new LinkedHashMap<>(args());
        other.put("job", "hourly-rollup");
        assertTrue(approvals.find(request("job_run", other, "run-C-reissue")).isEmpty());   // args differ
        assertTrue(approvals.find(request("pipeline_rerun", args(), "run-C-x")).isEmpty()); // tool differs
        // The correct identical call still consumes it.
        assertEquals(ApprovalDecision.APPROVED,
                approvals.find(request("job_run", args(), "run-C-ok")).orElseThrow());
    }

    @Test
    void anExpiredDecisionIsNotConsumed(@TempDir Path dir) {
        Path file = dir.resolve("approvals.jsonl");
        ApprovalStore store = new ApprovalStore(file);
        AgentApprovals approvals = new AgentApprovals(store, call -> Map.of());
        java.time.Instant old = java.time.Instant.now().minus(AgentApprovals.RESUME_TOKEN_TTL).minusSeconds(60);
        Approval a = new Approval("id-old", "job_run", "agent:run-D", "s", args(), Map.of(), old);
        store.add(a);
        store.transition("id-old", Approval.Status.APPROVED, "operator", old); // decided long ago

        assertTrue(approvals.find(request("job_run", args(), "run-D-reissue")).isEmpty());
    }

    @Test
    void durableStoreReloadsPendingAndDecidedAcrossRestart(@TempDir Path dir) {
        Path file = dir.resolve("approvals.jsonl");
        ApprovalStore first = new ApprovalStore(file);
        first.add(new Approval("p1", "job_run", "agent:1", "s", args(), Map.of("k", "v"), java.time.Instant.now()));
        first.add(new Approval("d1", "alert_ack", "agent:2", "s", Map.of("id", "o-9"), Map.of(), java.time.Instant.now()));
        first.transition("d1", Approval.Status.DENIED, "operator", java.time.Instant.now());

        ApprovalStore reloaded = new ApprovalStore(file);
        assertEquals(2, reloaded.size());
        assertEquals("PENDING", reloaded.byId("p1").orElseThrow().status().name());
        Approval d1 = reloaded.byId("d1").orElseThrow();
        assertEquals(Approval.Status.DENIED, d1.status());
        assertEquals("o-9", d1.arguments().get("id"));
        assertFalse(reloaded.byId("p1").orElseThrow().consumed());
    }
}
