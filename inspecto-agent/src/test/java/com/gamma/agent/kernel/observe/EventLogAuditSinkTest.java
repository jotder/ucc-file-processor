package com.gamma.agent.kernel.observe;

import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.error.AgentError;
import com.gamma.agent.kernel.model.ModelTier;
import com.gamma.event.EventLog;
import com.gamma.signal.Signal;
import com.gamma.signal.Signals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1 (event-signal-backbone-plan §4.2): {@link EventLogAuditSink} bridges the agent-kernel's
 * {@link AgentEvent} island onto the canonical ledger as {@code agent.*} Signals with
 * {@code correlationId} = the capability invocation id — proving the island is reconnected, not
 * merely available-but-unused (as {@link RingBufferAuditSink} was).
 *
 * <p>Each test registers its own {@link EventLog} under a unique space id (the {@code AcquisitionLedgersTest}
 * idiom) rather than reading {@link EventLog#global()}'s ring buffer — the shared global log is flooded by
 * the rest of the reactor's tests and would make these assertions flaky.
 */
class EventLogAuditSinkTest {

    private String space;
    private EventLog log;

    @BeforeEach
    void isolateEventLog() {
        space = "event-log-audit-sink-test-" + UUID.randomUUID();
        log = EventLog.create();
        EventLog.register(space, log);
        MDC.put(EventLog.SPACE_MDC_KEY, space);
    }

    @AfterEach
    void restoreEventLog() {
        MDC.remove(EventLog.SPACE_MDC_KEY);
        EventLog.unregister(space);
    }

    @Test
    void bridgesAgentStartedAndCompletedAsCorrelatedSignals() {
        String capabilityId = "explain-entity-" + UUID.randomUUID();
        EventLogAuditSink sink = new EventLogAuditSink();

        sink.emit(new AgentStarted(capabilityId, System.currentTimeMillis(), Set.of("entityType", "id")));
        sink.emit(new AgentCompleted(capabilityId, System.currentTimeMillis(), AgentResult.Status.OK,
                3, 42L, Set.of("entityType"), ModelTier.SMALL, true, 0, 0.91, 120, 40));

        List<Signal> signals = Signals.query(log.store(), "agent.run.*",
                null, null, null, capabilityId, 10);

        assertEquals(2, signals.size(), "both AgentStarted and AgentCompleted must land on the ledger");
        assertTrue(signals.stream().allMatch(s -> capabilityId.equals(s.correlationId())),
                "correlationId must be the capability invocation id");
        assertTrue(signals.stream().anyMatch(s -> "agent.run.started".equals(s.type())));
        assertTrue(signals.stream().anyMatch(s -> "agent.run.completed".equals(s.type())));
    }

    @Test
    void bridgesAgentFailedWithErrorSeverity() {
        String capabilityId = "kpi-to-sql-" + UUID.randomUUID();
        EventLogAuditSink sink = new EventLogAuditSink();

        sink.emit(new AgentFailed(capabilityId, System.currentTimeMillis(), AgentError.Category.MODEL, "timeout"));

        List<Signal> signals = Signals.query(log.store(), "agent.run.failed",
                null, null, null, capabilityId, 10);
        assertEquals(1, signals.size());
        assertEquals(com.gamma.signal.Severity.ERROR, signals.get(0).severity());
        assertEquals("timeout", signals.get(0).payload().get("reason"));
    }

    @Test
    void bridgesToolCalledAndCompleted() {
        String capabilityId = "diagnose-" + UUID.randomUUID();
        EventLogAuditSink sink = new EventLogAuditSink();

        sink.emit(new ToolCalled(capabilityId, System.currentTimeMillis(), "docs_search"));
        sink.emit(new ToolCompleted(capabilityId, System.currentTimeMillis(), "docs_search", 2, 15L));

        List<Signal> signals = Signals.query(log.store(), "agent.tool.*",
                null, null, null, capabilityId, 10);
        assertEquals(2, signals.size());
        assertTrue(signals.stream().anyMatch(s -> "agent.tool.called".equals(s.type())));
        assertTrue(signals.stream().anyMatch(s -> "agent.tool.completed".equals(s.type())));
    }
}
