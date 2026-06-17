package com.gamma.flow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 — {@link FlowTrigger}: parsing the entry-node {@code trigger:} config into a typed trigger and
 * classifying which of the two schedulers (§3.8) drives the flow; plus the duration convention.
 */
class FlowTriggerTest {

    private static FlowNode entry(Map<String, Object> trigger) {
        return FlowNode.of("acq", "acquisition", trigger == null ? Map.of() : Map.of("trigger", trigger));
    }

    @Test
    void scheduleIntervalIsLoopDriven() {
        FlowTrigger t = FlowTrigger.of(entry(Map.of("type", "schedule", "every", "60s")));
        assertEquals(FlowTrigger.Kind.SCHEDULE_INTERVAL, t.kind());
        assertEquals(60_000L, t.everyMs());
        assertEquals(FlowTrigger.Scheduler.LOOP, t.scheduler());
    }

    @Test
    void scheduleCronIsLoopDrivenAndCarriesTheExpression() {
        FlowTrigger t = FlowTrigger.of(entry(Map.of("type", "schedule", "cron", "0 */5 * * * *")));
        assertEquals(FlowTrigger.Kind.SCHEDULE_CRON, t.kind());
        assertEquals("0 */5 * * * *", t.cron());
        assertEquals(FlowTrigger.Scheduler.LOOP, t.scheduler());
    }

    @Test
    void eventTriggerClassifiesAsEventAndCoalesces() {
        FlowTrigger t = FlowTrigger.of(entry(Map.of(
                "type", "event", "on", "commit", "from", "flows/cdr_etl", "coalesce", "30s")));
        assertEquals(FlowTrigger.Kind.EVENT, t.kind());
        assertEquals("commit", t.on());
        assertEquals("flows/cdr_etl", t.from());
        assertEquals(30_000L, t.coalesceMs());
        assertTrue(t.coalesces());
        assertEquals(FlowTrigger.Scheduler.EVENT, t.scheduler());
    }

    @Test
    void manualTriggerClassifiesAsManual() {
        FlowTrigger t = FlowTrigger.of(entry(Map.of("type", "manual")));
        assertEquals(FlowTrigger.Kind.MANUAL, t.kind());
        assertEquals(FlowTrigger.Scheduler.MANUAL, t.scheduler());
        assertFalse(t.coalesces());
    }

    @Test
    void absentTriggerIsDefaultPollLoop() {
        FlowTrigger t = FlowTrigger.of(entry(null));
        assertEquals(FlowTrigger.Kind.DEFAULT_POLL, t.kind());
        assertEquals(FlowTrigger.Scheduler.LOOP, t.scheduler());
    }

    @Test
    void ofGraphPicksTheEntryNode() {
        FlowGraph g = new FlowGraph("G", true,
                List.of(FlowNode.of("acq", "acquisition", Map.of("trigger", Map.of("type", "manual"))),
                        FlowNode.of("sink", "sink.persistent")),
                List.of(FlowEdge.data("acq", "sink")));
        assertEquals(FlowTrigger.Kind.MANUAL, FlowTrigger.of(g).kind());
    }

    @Test
    void durationConventionAndUnknownType() {
        assertEquals(90_000L, FlowTrigger.millis("90"));       // bare number = seconds
        assertEquals(5 * 60_000L, FlowTrigger.millis("5m"));
        assertEquals(2 * 3_600_000L, FlowTrigger.millis("2h"));
        assertEquals(86_400_000L, FlowTrigger.millis("1d"));
        assertEquals(0L, FlowTrigger.millis(null));
        assertThrows(IllegalArgumentException.class,
                () -> FlowTrigger.of(entry(Map.of("type", "telepathy"))));
    }
}
