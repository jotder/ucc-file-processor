package com.gamma.intelligence.context;

import com.gamma.event.EventLog;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The triage ingress must keep only elevated signals, newest first, and — because it runs on the
 * {@code ingestLock}-holding emit thread — must shed rather than block when its hand-off queue is
 * full. A synchronous executor ({@code Runnable::run}) drains deterministically for the test.
 */
class SignalIngressTest {

    private static final Instant T0 = Instant.parse("2026-07-19T10:00:00Z");

    private static Signal sig(String id, String type, Severity sev, Instant at) {
        return new Signal(id, type, at, sev, Ref.of("pipeline", "p"), Ref.of("pipeline", "p"),
                null, null, null, null, "msg-" + id, Map.of(), 1);
    }

    @Test
    void elevatedIsSeverityFloorOrAFailureType() {
        assertTrue(SignalIngress.elevated(sig("a", "whatever", Severity.ERROR, T0)));
        assertTrue(SignalIngress.elevated(sig("b", "whatever", Severity.CRITICAL, T0)));
        assertTrue(SignalIngress.elevated(sig("c", "pipeline.batch.failed", Severity.WARN, T0)), "failure type, WARN");
        assertTrue(SignalIngress.elevated(sig("d", "job.run.failed", Severity.WARN, T0)), "failure type, WARN");
        assertFalse(SignalIngress.elevated(sig("e", "job.run.rejected", Severity.WARN, T0)), "plain WARN, non-failure");
        assertFalse(SignalIngress.elevated(sig("f", "pipeline.batch.committed", Severity.INFO, T0)));
    }

    @Test
    void retainsOnlyElevatedSignalsNewestFirst() {
        EventLog log = EventLog.create();
        try (SignalIngress ingress = new SignalIngress(Runnable::run, 16)) {
            ingress.attach(log);
            log.emit(sig("e1", "pipeline.batch.failed", Severity.ERROR, T0).toEvent());
            log.emit(sig("i1", "pipeline.batch.committed", Severity.INFO, T0.plusSeconds(1)).toEvent());
            log.emit(sig("e2", "job.run.failed", Severity.WARN, T0.plusSeconds(2)).toEvent());

            List<Signal> recent = ingress.recent(10);
            assertEquals(List.of("e2", "e1"), recent.stream().map(Signal::signalId).toList(),
                    "the INFO commit is dropped; the two elevated signals return newest-first");
        }
    }

    @Test
    void shedsRatherThanBlocksWhenTheQueueIsFull() {
        EventLog log = EventLog.create();
        // A no-op executor never drains, so the bounded queue (capacity 1) fills and then sheds.
        try (SignalIngress ingress = new SignalIngress(r -> { }, 1)) {
            ingress.attach(log);
            for (int i = 0; i < 3; i++) {
                log.emit(sig("s" + i, "pipeline.batch.failed", Severity.ERROR, T0.plusSeconds(i)).toEvent());
            }
            assertEquals(2, ingress.droppedCount(), "1 event fits the queue; the next 2 are shed, never blocked");
        }
    }
}
