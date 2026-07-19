package com.gamma.intelligence.pack;

import com.eoiagent.model.StubLlmGateway;
import com.gamma.intelligence.investigation.Case;
import com.gamma.intelligence.investigation.Incident;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden test of the AGT-5 P1 slice C RCA playbook (the P1 exit criterion): a seeded incident —
 * a broken batch (FAILED signal on the ledger) plus a config change (two archived versions of an
 * expectation) — yields, deterministically under a stub gateway, a ranked RCA Case grounded in the
 * real tool-gathered evidence with a persisted DRAFT fix.
 */
class InvestigatorTest {

    private static final int WIDE_WINDOW = 1_000_000; // minutes — sidesteps the tools' time floors

    // A scripted, well-formed RCA synthesis: two ranked hypotheses + a concrete fix draft.
    private static final String RCA_JSON = """
            Here is my analysis:
            ```json
            {
              "hypotheses": [
                {"cause": "the AMT expectation bound was relaxed from >=0 to >=-100",
                 "confidence": 0.8, "evidence": ["configChanges", "timeline"]},
                {"cause": "an upstream source shift", "confidence": 0.3, "evidence": ["timeline"]}
              ],
              "outcome": "relaxed AMT expectation admitted negative rows, tripping the batch",
              "fixDraft": {"kind": "expectation", "id": "amt-nonneg-fix",
                           "config": {"expr": "AMT >= 0", "severity": "error"}}
            }
            ```
            """;

    private static Signal failedBatch(Instant at) {
        return new Signal("f1", "pipeline.batch.failed", at, Severity.ERROR,
                Ref.of("pipeline", "mini_etl"), Ref.of("pipeline", "mini_etl"),
                "rca-corr", null, null, null, "batch b9 failed", Map.of(), 1);
    }

    @Test
    void seededIncidentYieldsRankedRcaWithEvidenceAndPersistedFixDraft(@TempDir Path dir) throws Exception {
        // Config change: two versions of an expectation (v1 archived, v2 live) → config_versions_diff has data.
        ComponentStore components = new ComponentStore(dir.resolve("registry"));
        components.write("expectation", "amt-nonneg", Map.of("expr", "AMT >= 0", "severity", "warn"));
        components.write("expectation", "amt-nonneg", Map.of("expr", "AMT >= -100", "severity", "warn"));

        // Broken batch: a FAILED signal on the JVM-wide ledger → timeline_build surfaces it.
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        svc.events().append(failedBatch(Instant.now()).toEvent());

        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText(RCA_JSON).build();
        Investigator investigator = new Investigator(svc, components, List::of, gateway);

        // Note: focusType/focusId drive config_versions_diff; no `focus` so the timeline keeps the signal too.
        Incident incident = new Incident("incident:1", Map.of("type", "pipeline.batch.failed"),
                Map.of("sinceMinutes", WIDE_WINDOW, "focusType", "expectation", "focusId", "amt-nonneg"));

        Case c = investigator.investigate(incident);

        // Ranked hypotheses, most-likely first.
        assertEquals(2, c.hypotheses().size());
        assertEquals(0.8, ((Number) c.hypotheses().get(0).get("confidence")).doubleValue(), 1e-9);
        assertTrue(c.hypotheses().get(0).get("cause").toString().contains("relaxed"));
        assertTrue(((Number) c.hypotheses().get(0).get("confidence")).doubleValue()
                >= ((Number) c.hypotheses().get(1).get("confidence")).doubleValue(), "ranked by confidence");
        assertTrue(c.outcome().contains("negative rows"));

        // Evidence is real: the timeline snapshot captured the seeded failure.
        assertFalse(c.timeline().isEmpty(), "timeline evidence gathered by the tool");

        // Fix draft persisted as a DRAFT component with actor audit.
        assertEquals(List.of("component:expectation/amt-nonneg-fix"), c.fixDraftRefs());
        var draft = components.get("expectation", "amt-nonneg-fix").orElseThrow().content();
        assertEquals("draft", draft.get("status"));
        assertEquals("agent:rca", draft.get("authoredBy"));
        assertEquals("AMT >= 0", draft.get("expr"));
    }

    @Test
    void unparseableSynthesisFilesAnInconclusiveCaseRatherThanThrowing(@TempDir Path dir) {
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText("sorry, I can't tell").build();
        Investigator investigator = new Investigator(svc, null, List::of, gateway);

        Case c = investigator.investigate(new Incident("incident:2", Map.of(), Map.of("sinceMinutes", WIDE_WINDOW)));

        assertTrue(c.hypotheses().isEmpty());
        assertTrue(c.outcome().startsWith("inconclusive"));
        assertTrue(c.fixDraftRefs().isEmpty());
    }

    @Test
    void fixDraftIsNotPersistedWhenNoComponentWriteRootIsConfigured(@TempDir Path dir) {
        CollectorService svc = new CollectorService(List.of(), 3600, 1);
        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText(RCA_JSON).build();
        Investigator investigator = new Investigator(svc, null, List::of, gateway); // no ComponentStore

        Case c = investigator.investigate(new Incident("incident:3", Map.of(), Map.of("sinceMinutes", WIDE_WINDOW)));

        assertEquals(2, c.hypotheses().size(), "hypotheses still produced");
        assertTrue(c.fixDraftRefs().isEmpty(), "no write root → draft not persisted");
    }
}
