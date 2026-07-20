package com.gamma.intelligence.action;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for the P3 approval spine: the blocking eoiagent-gate ↔ async-inbox bridge
 * ({@link AgentApprovals}) and the guarded once-only transition ({@link ApprovalStore}). No eoiagent
 * platform / model is involved — {@code decide(req)} is exercised directly on a worker thread.
 */
class AgentApprovalsTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static ApprovalRequest request(String tool, String runId) {
        ToolCall call = new ToolCall(tool, Map.of("type", "expectation", "id", "amt-nonneg"), new RunId(runId));
        return new ApprovalRequest(call.run(), call, "Approve mutating action '" + tool + "'",
                new DryRunResult(false, "", Map.of()));
    }

    /** Poll the inbox for the single pending approval to appear (decide runs on another thread). */
    private static Map<String, Object> awaitPending(AgentApprovals approvals) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<Map<String, Object>> pending = approvals.recent(10).stream()
                    .filter(a -> "PENDING".equals(a.get("status"))).findFirst();
            if (pending.isPresent()) return pending.get();
            Thread.sleep(10);
        }
        fail("no pending approval appeared");
        throw new AssertionError("unreachable");
    }

    @Test
    void approveUnblocksTheGateAndMarksTheApprovalApproved() throws Exception {
        AgentApprovals approvals = new AgentApprovals();
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("component_apply", "run-1")));

        Map<String, Object> pending = awaitPending(approvals);
        String id = (String) pending.get("id");
        assertEquals("agent:run-1", pending.get("agentActor"));

        Optional<Map<String, Object>> view = approvals.resolve(id, true, "alice");
        assertTrue(view.isPresent());
        assertEquals("APPROVED", view.get().get("status"));
        assertEquals("alice", view.get().get("decidedBy"));
        assertEquals(ApprovalDecision.APPROVED, gate.get(2, TimeUnit.SECONDS));
    }

    @Test
    void declineUnblocksTheGateWithDenied() throws Exception {
        AgentApprovals approvals = new AgentApprovals();
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("component_apply", "run-2")));

        String id = (String) awaitPending(approvals).get("id");
        Optional<Map<String, Object>> view = approvals.resolve(id, false, "bob");
        assertTrue(view.isPresent());
        assertEquals("DENIED", view.get().get("status"));
        assertEquals(ApprovalDecision.DENIED, gate.get(2, TimeUnit.SECONDS));
    }

    @Test
    void resolvingAnUnknownIdIsEmpty() {
        AgentApprovals approvals = new AgentApprovals();
        assertTrue(approvals.resolve("nope", true, "alice").isEmpty());
        assertTrue(approvals.byId("nope").isEmpty());
    }

    @Test
    void aSecondDecisionIsANoOp() throws Exception {
        AgentApprovals approvals = new AgentApprovals();
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("component_apply", "run-3")));

        String id = (String) awaitPending(approvals).get("id");
        assertTrue(approvals.resolve(id, true, "alice").isPresent());
        assertEquals(ApprovalDecision.APPROVED, gate.get(2, TimeUnit.SECONDS));
        // Already APPROVED — a racing/second decision can never re-open it.
        assertTrue(approvals.resolve(id, false, "mallory").isEmpty());
        assertEquals("APPROVED", approvals.byId(id).orElseThrow().get("status"));
    }

    @Test
    void aCancelledGateThreadLapsesTheApprovalAsTimedOut() throws Exception {
        AgentApprovals approvals = new AgentApprovals();
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("job_run", "run-4")));

        String id = (String) awaitPending(approvals).get("id");
        gate.cancel(true); // the real CallbackApprovalGate cancels the handler task when its timeout elapses

        for (int i = 0; i < 200 && !"TIMED_OUT".equals(approvals.byId(id).orElseThrow().get("status")); i++) {
            Thread.sleep(10);
        }
        assertEquals("TIMED_OUT", approvals.byId(id).orElseThrow().get("status"));
        // A late operator decision on a lapsed request is rejected (the route maps empty → 404).
        assertTrue(approvals.resolve(id, true, "late").isEmpty());
    }

    @Test
    void theDryRunPreviewIsComputedAndSurfacedInTheView() throws Exception {
        AgentApprovals approvals = new AgentApprovals(new ApprovalStore(),
                call -> Map.of("diff", "+1 field, -0 fields", "target", call.arguments().get("id")));
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("component_apply", "run-5")));

        Map<String, Object> pending = awaitPending(approvals);
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) pending.get("preview");
        assertEquals("+1 field, -0 fields", preview.get("diff"));
        assertEquals("amt-nonneg", preview.get("target"));

        approvals.resolve((String) pending.get("id"), true, "alice");
        assertEquals(ApprovalDecision.APPROVED, gate.get(2, TimeUnit.SECONDS));
    }

    @Test
    void aFailingPreviewNeverBlocksTheRequest() throws Exception {
        AgentApprovals approvals = new AgentApprovals(new ApprovalStore(),
                call -> { throw new IllegalStateException("boom"); });
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("component_apply", "run-6")));

        Map<String, Object> pending = awaitPending(approvals);
        @SuppressWarnings("unchecked")
        Map<String, Object> preview = (Map<String, Object>) pending.get("preview");
        assertEquals("boom", preview.get("previewError"));

        approvals.resolve((String) pending.get("id"), true, "alice");
        assertEquals(ApprovalDecision.APPROVED, gate.get(2, TimeUnit.SECONDS));
    }

    @Test
    void toViewCarriesTheRequestFactsAndNullDecisionWhilePending() throws Exception {
        AgentApprovals approvals = new AgentApprovals();
        Future<ApprovalDecision> gate = pool.submit(() -> approvals.decide(request("pipeline_rerun", "run-7")));
        try {
            Map<String, Object> v = awaitPending(approvals);
            assertEquals("pipeline_rerun", v.get("tool"));
            assertEquals("amt-nonneg", ((Map<?, ?>) v.get("arguments")).get("id"));
            assertEquals("PENDING", v.get("status"));
            assertNull(v.get("decidedAt"));
            assertNull(v.get("decidedBy"));
        } finally {
            approvals.recent(10).stream().findFirst().ifPresent(a -> approvals.resolve((String) a.get("id"), false, "cleanup"));
            gate.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void storeIsBoundedAndNewestFirst() {
        ApprovalStore store = new ApprovalStore(2);
        store.add(new Approval("a", "t", "agent:1", "s", Map.of(), Map.of(), java.time.Instant.now()));
        store.add(new Approval("b", "t", "agent:1", "s", Map.of(), Map.of(), java.time.Instant.now()));
        store.add(new Approval("c", "t", "agent:1", "s", Map.of(), Map.of(), java.time.Instant.now()));
        assertEquals(2, store.size()); // oldest ("a") evicted
        List<Approval> recent = store.recent(10);
        assertEquals("c", recent.get(0).id()); // newest first
        assertEquals("b", recent.get(1).id());
        assertTrue(store.byId("a").isEmpty());
    }

    @Test
    void transitionIsOnceOnlyAndGuardsAgainstDoubleDecision() {
        ApprovalStore store = new ApprovalStore();
        Approval a = new Approval("x", "t", "agent:1", "s", Map.of(), Map.of(), java.time.Instant.now());
        store.add(a);
        Optional<Approval> first = store.transition("x", Approval.Status.APPROVED, "alice", java.time.Instant.now());
        assertTrue(first.isPresent());
        assertSame(a, first.get());
        assertEquals(Approval.Status.APPROVED, a.status());
        assertTrue(store.transition("x", Approval.Status.DENIED, "bob", java.time.Instant.now()).isEmpty());
        assertEquals(Approval.Status.APPROVED, a.status()); // unchanged
        assertFalse(store.byId("x").isEmpty());
    }
}
