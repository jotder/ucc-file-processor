package com.gamma.intelligence.pack;

import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.tool.Tool;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct (no-LLM) tests of the S5 signal tools: they read the canonical ledger through
 * {@code CollectorService.events()} — the same store {@code EventLog.emit} writes to — so a seeded
 * failure chain must come back filtered ({@code signals_query}) and causation-ordered
 * ({@code signal_timeline}), with signal ids usable as citations.
 *
 * <p>The default-space {@link CollectorService} rides {@code EventLog.global()}, a JVM-wide ledger
 * other tests also emit onto, so every query here is scoped by a unique {@code correlationId} —
 * that isolates the assertions from whatever else the reactor emitted.
 */
class InspectoToolsTest {

    private static final Instant T0 = Instant.parse("2026-07-19T10:00:00Z");
    private static final int WIDE_WINDOW = 1_000_000; // minutes — sidesteps the default 60-min/24-h floors

    private static CollectorService seeded(Signal... signals) {
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        for (Signal s : signals) svc.events().append(s.toEvent());
        return svc;
    }

    private static Signal sig(String id, String type, Severity sev, String corr, String causedBy, Instant at) {
        return new Signal(id, type, at, sev, Ref.of("pipeline", "p"), Ref.of("pipeline", "p"),
                corr, causedBy, null, null, "msg-" + id, Map.of(), 1);
    }

    private static Tool tool(CollectorService svc, String name) {
        return InspectoTools.tools(svc).stream()
                .filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(CollectorService svc, String name, Map<String, Object> args) {
        ToolResult r = tool(svc, name).invoke(new ToolCall(name, args, new RunId("t")));
        assertTrue(r.ok(), () -> "tool errored: " + r.error());
        return (Map<String, Object>) r.value();
    }

    @Test
    void signalsQueryFiltersByTypeGlobAndSeverityFloor() {
        String corr = "s5q-filter";
        CollectorService svc = seeded(
                sig("s1", "s5q.batch.failed", Severity.ERROR, corr, null, T0),
                sig("s2", "s5q.job.failed", Severity.ERROR, corr, "s1", T0.plusSeconds(1)),
                sig("s3", "s5q.batch.ok", Severity.INFO, corr, null, T0.plusSeconds(2)));

        Map<String, Object> byType = invoke(svc, "signals_query",
                Map.of("type", "s5q.batch.*", "correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(2, byType.get("count"), "both s5q.batch.* signals, either severity");

        Map<String, Object> bySeverity = invoke(svc, "signals_query",
                Map.of("minSeverity", "error", "correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(2, bySeverity.get("count"), "the two ERROR signals only — the INFO commit is below the floor");

        Map<String, Object> job = invoke(svc, "signals_query",
                Map.of("type", "s5q.job.*", "correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(1, job.get("count"));
    }

    @Test
    void signalsQueryReportsAnUnknownSeverityAsAnErrorResultNotAThrow() {
        ToolResult r = tool(seeded(), "signals_query")
                .invoke(new ToolCall("signals_query", Map.of("minSeverity", "nonsense"), new RunId("t")));
        assertFalse(r.ok());
        assertTrue(r.error().contains("severity"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void signalTimelineReturnsTheCausationOrderedChainWithCitableIds() {
        String corr = "s5tl-chain";
        CollectorService svc = seeded(
                // deliberately appended child-first to prove ordering is by causation, not insertion
                sig("s2", "s5q.job.failed", Severity.ERROR, corr, "s1", T0.plusSeconds(1)),
                sig("s1", "s5q.batch.failed", Severity.ERROR, corr, null, T0),
                sig("other", "s5q.batch.ok", Severity.INFO, "s5tl-other", null, T0));

        Map<String, Object> out = invoke(svc, "signal_timeline",
                Map.of("correlationId", corr, "sinceMinutes", WIDE_WINDOW));
        assertEquals(corr, out.get("correlationId"));
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) out.get("timeline");
        assertEquals(2, timeline.size(), "only this correlationId's signals");
        assertEquals("s1", timeline.get(0).get("signalId"), "root (no causation) first");
        assertNull(timeline.get(0).get("causedBy"));
        assertEquals("s2", timeline.get(1).get("signalId"), "the caused signal follows its cause");
        assertEquals("s1", timeline.get(1).get("causedBy"), "causedBy is a citable parent signal id");
    }

    @Test
    void signalTimelineNarratesAnAgentRunFromItsOwnFacts() {
        String cap = "s5tl-cap-42";
        CollectorService svc = seeded(
                sig("a2", "agent.run.completed", Severity.INFO, cap, "a1", T0.plusSeconds(3)),
                sig("a1", "agent.run.started", Severity.INFO, cap, null, T0));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>)
                invoke(svc, "signal_timeline", Map.of("correlationId", cap, "sinceMinutes", WIDE_WINDOW)).get("timeline");
        assertEquals(List.of("agent.run.started", "agent.run.completed"),
                timeline.stream().map(e -> e.get("type")).toList());
    }

    @Test
    void signalTimelineWithNoMatchesIsAnErrorResult() {
        ToolResult r = tool(seeded(), "signal_timeline").invoke(new ToolCall("signal_timeline",
                Map.of("correlationId", "s5tl-absent-xyz", "sinceMinutes", WIDE_WINDOW), new RunId("t")));
        assertFalse(r.ok());
    }
}
