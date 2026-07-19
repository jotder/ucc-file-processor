package com.gamma.intelligence.investigation;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventLevel;
import com.gamma.event.EventType;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slice E triage dispatcher behavior — driven with a synchronous executor ({@code Runnable::run}) over
 * an isolated {@link EventLog#create()} so every assertion is deterministic. Proves the error/critical
 * floor, the {@code agent.*} exclusion (no self-investigation loop), correlationId dedupe, and that
 * autonomy is off by default.
 */
class TriageQueueTest {

    private static Signal signal(String type, Severity sev, String corr, Ref subject, Map<String, Object> payload) {
        return new Signal(null, type, Instant.now(), sev, Ref.of("pipeline", "p"), subject,
                corr, null, null, null, "msg", payload, 1);
    }

    private record Harness(EventLog log, TriageQueue triage, List<Incident> investigated) implements AutoCloseable {
        public void close() { triage.close(); }
    }

    private static Harness attach() {
        EventLog log = EventLog.create();
        List<Incident> investigated = new CopyOnWriteArrayList<>();
        TriageQueue triage = new TriageQueue(investigated::add, Runnable::run, 64);
        triage.attach(log);
        return new Harness(log, triage, investigated);
    }

    @Test
    void errorSignalTriggersExactlyOneInvestigation() {
        try (Harness h = attach()) {
            h.log().emit(signal("pipeline.batch.failed", Severity.ERROR, "corr-1",
                    Ref.of("pipeline", "mini_etl"), Map.of()).toEvent());
            assertEquals(1, h.investigated().size());
            Incident inc = h.investigated().get(0);
            assertEquals("corr-1", inc.incidentRef());
            assertEquals("mini_etl", inc.params().get("pipeline"), "pipeline subject flows into the tool params");
        }
    }

    @Test
    void criticalExpectationSignalCarriesTheFocusComponent() {
        try (Harness h = attach()) {
            h.log().emit(signal("expectation.violated", Severity.CRITICAL, "expectation:amt-nonneg",
                    Ref.of("dataset", "sales"), Map.of("expectation", "amt-nonneg")).toEvent());
            assertEquals(1, h.investigated().size());
            Incident inc = h.investigated().get(0);
            assertEquals("expectation", inc.params().get("focusType"));
            assertEquals("amt-nonneg", inc.params().get("focusId"));
        }
    }

    @Test
    void agentSignalsAreNeverInvestigated() {
        try (Harness h = attach()) {
            h.log().emit(signal("agent.tool.invoked", Severity.ERROR, "corr-agent", null, Map.of()).toEvent());
            assertTrue(h.investigated().isEmpty(), "the agent must not chase its own telemetry");
        }
    }

    @Test
    void signalsBelowTheErrorFloorAreIgnored() {
        try (Harness h = attach()) {
            h.log().emit(signal("alert-rule.fired", Severity.WARN, "corr-warn", null, Map.of()).toEvent());
            assertTrue(h.investigated().isEmpty());
        }
    }

    @Test
    void aRepeatedCorrelationIdIsInvestigatedOnce() {
        try (Harness h = attach()) {
            h.log().emit(signal("alert-rule.fired", Severity.ERROR, "corr-dup", null, Map.of()).toEvent());
            h.log().emit(signal("alert-rule.fired", Severity.ERROR, "corr-dup", null, Map.of()).toEvent());
            assertEquals(1, h.investigated().size(), "a re-fire of the same breach is deduped");
        }
    }

    @Test
    void nonSignalEventsAreIgnored() {
        try (Harness h = attach()) {
            h.log().emit(Event.builder(EventType.BATCH_FAILED).level(EventLevel.INFO).message("done"));
            assertTrue(h.investigated().isEmpty());
        }
    }

    @Test
    void triageIsDisabledByDefault() {
        assertFalse(TriageQueue.enabled(), "autonomy is opt-in (-Dintelligence.triage.enabled)");
    }
}
