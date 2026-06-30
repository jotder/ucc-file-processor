package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T13 — {@link PipelineTrigger}: parsing the entry-node {@code trigger:} config into a typed trigger and
 * classifying which of the two schedulers (§3.8) drives the flow; plus the duration convention.
 */
class PipelineTriggerTest {

    private static PipelineNode entry(Map<String, Object> trigger) {
        return PipelineNode.of("acq", "acquisition", trigger == null ? Map.of() : Map.of("trigger", trigger));
    }

    @Test
    void scheduleIntervalIsLoopDriven() {
        PipelineTrigger t = PipelineTrigger.of(entry(Map.of("type", "schedule", "every", "60s")));
        assertEquals(PipelineTrigger.Kind.SCHEDULE_INTERVAL, t.kind());
        assertEquals(60_000L, t.everyMs());
        assertEquals(PipelineTrigger.Scheduler.LOOP, t.scheduler());
    }

    @Test
    void scheduleCronIsLoopDrivenAndCarriesTheExpression() {
        PipelineTrigger t = PipelineTrigger.of(entry(Map.of("type", "schedule", "cron", "0 */5 * * * *")));
        assertEquals(PipelineTrigger.Kind.SCHEDULE_CRON, t.kind());
        assertEquals("0 */5 * * * *", t.cron());
        assertEquals(PipelineTrigger.Scheduler.LOOP, t.scheduler());
    }

    @Test
    void eventTriggerClassifiesAsEventAndCoalesces() {
        PipelineTrigger t = PipelineTrigger.of(entry(Map.of(
                "type", "event", "on", "commit", "from", "flows/cdr_etl", "coalesce", "30s")));
        assertEquals(PipelineTrigger.Kind.EVENT, t.kind());
        assertEquals("commit", t.on());
        assertEquals("flows/cdr_etl", t.from());
        assertEquals(30_000L, t.coalesceMs());
        assertTrue(t.coalesces());
        assertEquals(PipelineTrigger.Scheduler.EVENT, t.scheduler());
    }

    @Test
    void manualTriggerClassifiesAsManual() {
        PipelineTrigger t = PipelineTrigger.of(entry(Map.of("type", "manual")));
        assertEquals(PipelineTrigger.Kind.MANUAL, t.kind());
        assertEquals(PipelineTrigger.Scheduler.MANUAL, t.scheduler());
        assertFalse(t.coalesces());
    }

    @Test
    void absentTriggerIsDefaultPollLoop() {
        PipelineTrigger t = PipelineTrigger.of(entry(null));
        assertEquals(PipelineTrigger.Kind.DEFAULT_POLL, t.kind());
        assertEquals(PipelineTrigger.Scheduler.LOOP, t.scheduler());
    }

    @Test
    void ofGraphPicksTheEntryNode() {
        PipelineGraph g = new PipelineGraph("G", true,
                List.of(PipelineNode.of("acq", "acquisition", Map.of("trigger", Map.of("type", "manual"))),
                        PipelineNode.of("sink", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "sink")));
        assertEquals(PipelineTrigger.Kind.MANUAL, PipelineTrigger.of(g).kind());
    }

    @Test
    void durationConventionAndUnknownType() {
        assertEquals(90_000L, PipelineTrigger.millis("90"));       // bare number = seconds
        assertEquals(5 * 60_000L, PipelineTrigger.millis("5m"));
        assertEquals(2 * 3_600_000L, PipelineTrigger.millis("2h"));
        assertEquals(86_400_000L, PipelineTrigger.millis("1d"));
        assertEquals(0L, PipelineTrigger.millis(null));
        assertThrows(IllegalArgumentException.class,
                () -> PipelineTrigger.of(entry(Map.of("type", "telepathy"))));
    }
}
