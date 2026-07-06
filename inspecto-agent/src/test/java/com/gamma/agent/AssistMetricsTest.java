package com.gamma.agent;

import com.gamma.agent.kernel.agent.AgentResult;
import com.gamma.agent.kernel.observe.AgentCompleted;
import com.gamma.agent.kernel.observe.AgentEvent;
import com.gamma.agent.kernel.observe.AuditSink;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Per-intent counters (v4.1, B2): tally OK/unavailable/repaired and forward to the wrapped sink. */
class AssistMetricsTest {

    private static AgentCompleted event(String intent, AgentResult.Status status, int repairRounds,
                                        long ms, double confidence) {
        return new AgentCompleted(intent, 1L, status, 1, ms, Set.of(), null, true,
                repairRounds, confidence, 0, 0);
    }

    @Test
    void talliesPerIntentAndForwards() {
        List<AgentEvent> forwarded = new ArrayList<>();
        AssistMetrics metrics = new AssistMetrics(forwarded::add);

        metrics.emit(event("kpi-to-sql", AgentResult.Status.OK, 0, 100, 1.0));
        metrics.emit(event("kpi-to-sql", AgentResult.Status.OK, 1, 300, 0.9));
        metrics.emit(event("kpi-to-sql", AgentResult.Status.UNAVAILABLE, 0, 50, 0.3));
        metrics.emit(event("explain-entity", AgentResult.Status.OK, 0, 80, 0.6));

        Map<String, Object> snap = metrics.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> kpi = (Map<String, Object>) snap.get("kpi-to-sql");
        assertEquals(3L, kpi.get("calls"));
        assertEquals(2L, kpi.get("ok"));
        assertEquals(1L, kpi.get("unavailable"));
        assertEquals(1L, kpi.get("repaired"), "the abstained call carried no repair");
        assertEquals(150L, kpi.get("avgMs"));
        assertEquals(0.3, kpi.get("lastConfidence"), "last call's confidence (the abstention)");

        assertEquals(4, forwarded.size(), "decorator forwards every event unchanged");
    }

    @Test
    void nullDelegateIsSafe() {
        AssistMetrics metrics = new AssistMetrics(null);
        metrics.emit(event("report-sql", AgentResult.Status.OK, 0, 10, 1.0));
        assertEquals(1L, ((Map<?, ?>) metrics.snapshot().get("report-sql")).get("calls"));
    }
}
