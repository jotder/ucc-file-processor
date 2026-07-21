package com.gamma.signal;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgUiProjection#project} — the catalogued {@code agent.*} types project to their AG-UI
 * lifecycle/tool-call counterpart, and an uncatalogued domain type falls back to {@link
 * AgUiProjection#CUSTOM} without throwing (the mapping is total, event-signal-backbone-plan §S3).
 */
class AgUiProjectionTest {

    private static Signal sig(String type, String corr, String causation) {
        return new Signal("id-1", type, Instant.ofEpochMilli(1000), Severity.INFO,
                Ref.of("agent-capability", "cap-1"), null, corr, causation, "acme", null,
                "msg", Map.of("k", "v"), 1);
    }

    @Test
    void mapsEachCatalogueAgentTypeToItsAgUiCounterpart() {
        assertEquals("RUN_STARTED", AgUiProjection.project(sig("agent.run.started", "c1", null)).get("type"));
        assertEquals("RUN_FINISHED", AgUiProjection.project(sig("agent.run.completed", "c1", null)).get("type"));
        assertEquals("RUN_ERROR", AgUiProjection.project(sig("agent.run.failed", "c1", null)).get("type"));
        assertEquals("TOOL_CALL_START", AgUiProjection.project(sig("agent.tool.called", "c1", null)).get("type"));
        assertEquals("TOOL_CALL_END", AgUiProjection.project(sig("agent.tool.completed", "c1", null)).get("type"));
    }

    @Test
    void uncataloguedTypeFallsBackToCustomWithoutThrowing() {
        Map<String, Object> out = AgUiProjection.project(sig("recon.run.completed", "c2", "c1"));
        assertEquals(AgUiProjection.CUSTOM, out.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) out.get("raw");
        assertEquals("recon.run.completed", raw.get("domainType"), "the fallback carries the original domain type");
    }

    @Test
    void threadIdAndParentMessageIdCarryCorrelationAndCausation() {
        Map<String, Object> out = AgUiProjection.project(sig("agent.tool.called", "run-42", "parent-9"));
        assertEquals("run-42", out.get("threadId"));
        assertEquals("run-42", out.get("runId"));
        assertEquals("parent-9", out.get("parentMessageId"));
    }
}
