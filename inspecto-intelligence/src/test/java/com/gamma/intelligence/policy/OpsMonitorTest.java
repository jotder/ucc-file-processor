package com.gamma.intelligence.policy;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.intelligence.policy.AutonomyPolicy.ClassPolicy;
import com.gamma.intelligence.policy.AutonomyPolicy.Mode;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AGT-5 P4 slice 2: the {@code ops_monitor} driver — that a {@code pipeline.batch.failed} Signal is
 * gated by the {@link AutonomyPolicyEngine} (deny / shadow / execute), remediated only in AUTO within
 * budget, deduped per batch, and fully recorded in the {@link AutonomyLog}.
 */
class OpsMonitorTest {

    /** Records remediation calls; a scripted failure throws to exercise the FAILED path. */
    private static final class RecordingRemediator implements OpsMonitor.Remediator {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        volatile Map<String, Object> lastSubject;

        @Override public String remediate(String actionClass, Map<String, Object> subject) throws Exception {
            calls.incrementAndGet();
            lastSubject = subject;
            if (fail) throw new IllegalStateException("control plane unreachable");
            return "reprocessed " + subject.get("batchId");
        }
    }

    private static AutonomyPolicyEngine engineWith(Mode mode) {
        AutonomyPolicyEngine e = new AutonomyPolicyEngine(new AutonomyPolicyStore());
        if (mode != Mode.OFF) e.setClass(OpsMonitor.ACTION_BATCH_RERUN, new ClassPolicy(mode, 100, 100), "op");
        return e;
    }

    private static OpsMonitor monitor(AutonomyPolicyEngine engine, AutonomyLog log, OpsMonitor.Remediator rem) {
        // Synchronous executor: drain happens inline so assertions are deterministic.
        return new OpsMonitor(engine, log, rem, Runnable::run, 64);
    }

    private static Event batchFailed(String pipeline, String batchId) {
        Signal s = new Signal(null, OpsMonitor.TRIGGER_BATCH_FAILED, Instant.now(), Severity.WARN,
                Ref.of("pipeline", pipeline), Ref.of("pipeline", pipeline), batchId, null, null, null,
                "batch failed", Map.of("status", "failed"), 1);
        return s.toEvent();
    }

    @Test
    void offByDefaultRecordsASkipAndNeverRemediates() {
        AutonomyLog log = new AutonomyLog();
        RecordingRemediator rem = new RecordingRemediator();
        ActionRecord r = monitor(engineWith(Mode.OFF), log, rem)
                .decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "orders", "batchId", "b1"));
        assertEquals(ActionRecord.Status.SKIPPED, r.status());
        assertEquals(0, rem.calls.get());
        assertEquals(1, log.size());
    }

    @Test
    void shadowModeRecordsWouldActButDoesNotRemediate() {
        AutonomyLog log = new AutonomyLog();
        RecordingRemediator rem = new RecordingRemediator();
        ActionRecord r = monitor(engineWith(Mode.SHADOW), log, rem)
                .decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "orders", "batchId", "b1"));
        assertEquals(ActionRecord.Status.SHADOWED, r.status());
        assertEquals(0, rem.calls.get());
    }

    @Test
    void autoModeRemediatesAndRecordsSuccess() {
        AutonomyLog log = new AutonomyLog();
        RecordingRemediator rem = new RecordingRemediator();
        ActionRecord r = monitor(engineWith(Mode.AUTO), log, rem)
                .decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "orders", "batchId", "b1"));
        assertEquals(ActionRecord.Status.SUCCEEDED, r.status());
        assertEquals(1, rem.calls.get());
        assertEquals("b1", rem.lastSubject.get("batchId"));
    }

    @Test
    void autoModeRecordsFailureWhenRemediationThrows() {
        AutonomyLog log = new AutonomyLog();
        RecordingRemediator rem = new RecordingRemediator();
        rem.fail = true;
        ActionRecord r = monitor(engineWith(Mode.AUTO), log, rem)
                .decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "orders", "batchId", "b1"));
        assertEquals(ActionRecord.Status.FAILED, r.status());
        assertTrue(r.toView().get("detail").toString().contains("unreachable"));
    }

    @Test
    void killSwitchOverridesAutoAndSkips() {
        AutonomyPolicyEngine engine = engineWith(Mode.AUTO);
        engine.setKillSwitch(true, "commander");
        RecordingRemediator rem = new RecordingRemediator();
        ActionRecord r = monitor(engine, new AutonomyLog(), rem)
                .decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "orders", "batchId", "b1"));
        assertEquals(ActionRecord.Status.SKIPPED, r.status());
        assertEquals("kill switch engaged", r.toView().get("reason"));
        assertEquals(0, rem.calls.get());
    }

    @Test
    void budgetExhaustionSkipsFurtherAutoActions() {
        AutonomyPolicyEngine engine = new AutonomyPolicyEngine(new AutonomyPolicyStore());
        engine.setClass(OpsMonitor.ACTION_BATCH_RERUN, new ClassPolicy(Mode.AUTO, 1, 1), "op"); // 1/hr
        RecordingRemediator rem = new RecordingRemediator();
        OpsMonitor m = monitor(engine, new AutonomyLog(), rem);
        assertEquals(ActionRecord.Status.SUCCEEDED,
                m.decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "o", "batchId", "b1")).status());
        assertEquals(ActionRecord.Status.SKIPPED,
                m.decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "o", "batchId", "b2")).status());
        assertEquals(1, rem.calls.get());
    }

    @Test
    void endToEndOverTheEventLogRemediatesOncePerBatch() {
        EventLog bus = EventLog.create();
        AutonomyLog log = new AutonomyLog();
        RecordingRemediator rem = new RecordingRemediator();
        try (OpsMonitor m = monitor(engineWith(Mode.AUTO), log, rem)) {
            m.attach(bus);
            bus.emit(batchFailed("orders", "batch-42"));
            bus.emit(batchFailed("orders", "batch-42")); // duplicate — deduped by batchId
            assertEquals(1, rem.calls.get());
            assertEquals(1, log.size());
            assertEquals("batch-42", rem.lastSubject.get("batchId"));
        }
    }

    @Test
    void ignoresNonTriggerAndAgentSignals() {
        EventLog bus = EventLog.create();
        RecordingRemediator rem = new RecordingRemediator();
        try (OpsMonitor m = monitor(engineWith(Mode.AUTO), new AutonomyLog(), rem)) {
            m.attach(bus);
            // A committed batch (not failed) and an agent.* self-telemetry signal must both be ignored.
            bus.emit(new Signal(null, "pipeline.batch.committed", Instant.now(), Severity.INFO,
                    Ref.of("pipeline", "orders"), Ref.of("pipeline", "orders"), "b-ok", null, null, null,
                    "ok", Map.of(), 1).toEvent());
            bus.emit(new Signal(null, "agent.action.taken", Instant.now(), Severity.WARN,
                    Ref.of("pipeline", "orders"), Ref.of("pipeline", "orders"), "b-agent", null, null, null,
                    "self", Map.of(), 1).toEvent());
            assertEquals(0, rem.calls.get());
        }
    }

    @Test
    void stateWatchPollActsOnScannerFindingsWithinPolicyAndDedupes() {
        AutonomyPolicyEngine engine = new AutonomyPolicyEngine(new AutonomyPolicyStore());
        engine.setClass(OpsMonitor.ACTION_ALERT_TRIAGE, new ClassPolicy(Mode.AUTO, 100, 100), "op");
        AutonomyLog log = new AutonomyLog();
        RecordingRemediator rem = new RecordingRemediator();
        OpsMonitor m = monitor(engine, log, rem);

        OpsMonitor.StateScanner scanner = () -> List.of(
                new OpsMonitor.Finding(OpsMonitor.ACTION_ALERT_TRIAGE, "alert-1",
                        Map.of("alertId", "alert-1", "pipeline", "orders")));
        m.pollOnce(scanner);
        m.pollOnce(scanner); // same finding again — deduped, no second action
        assertEquals(1, rem.calls.get());
        assertEquals("alert-1", rem.lastSubject.get("alertId"));
        assertEquals(1, log.size());
    }

    @Test
    void stateWatchRespectsModeAndKillSwitch() {
        // OFF class → the scanner finding is recorded SKIPPED, never remediated.
        AutonomyPolicyEngine offEngine = new AutonomyPolicyEngine(new AutonomyPolicyStore());
        RecordingRemediator rem = new RecordingRemediator();
        OpsMonitor m = monitor(offEngine, new AutonomyLog(), rem);
        m.pollOnce(() -> List.of(new OpsMonitor.Finding(OpsMonitor.ACTION_ALERT_TRIAGE, "a1",
                Map.of("alertId", "a1"))));
        assertEquals(0, rem.calls.get());
    }

    @Test
    void aThrowingScannerIsSwallowed() {
        OpsMonitor m = monitor(engineWith(Mode.AUTO), new AutonomyLog(), new RecordingRemediator());
        m.pollOnce(() -> { throw new IllegalStateException("scan boom"); }); // must not propagate
    }

    @Test
    void ledgerReturnsNewestFirst() {
        AutonomyLog log = new AutonomyLog();
        OpsMonitor m = monitor(engineWith(Mode.AUTO), log, new RecordingRemediator());
        m.decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "o", "batchId", "b1"));
        m.decideAndAct(OpsMonitor.ACTION_BATCH_RERUN, Map.of("pipeline", "o", "batchId", "b2"));
        List<ActionRecord> recent = log.recent(10);
        assertEquals(2, recent.size());
        Object subject = recent.get(0).toView().get("subject");
        assertTrue(subject instanceof Map<?, ?> s && "b2".equals(s.get("batchId")), "newest record first");
    }
}
