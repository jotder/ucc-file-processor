package com.gamma.intelligence.investigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AGT-5 P5 slice 2: deterministic {@link CaseSimilarity} + durable {@link CaseStore} recall. */
class CaseRecallTest {

    private static Case caseFor(String id, String type, String pipeline, String hypothesis, Instant at) {
        Map<String, Object> signal = Map.of(
                "type", type,
                "subject", Map.of("kind", "pipeline", "id", pipeline),
                "message", type + " on " + pipeline);
        List<Map<String, Object>> hyps = List.of(Map.of("title", hypothesis));
        return new Case(id, "incident:" + id, signal, List.of(), hyps, "open", List.of(), at);
    }

    @Test
    void jaccardScoresOverlapAndIgnoresShortTokens() {
        double s = CaseSimilarity.score("pipeline batch failed orders", "pipeline batch failed orders");
        assertEquals(1.0, s, 1e-9);
        assertEquals(0.0, CaseSimilarity.score("", "anything"));
        assertTrue(CaseSimilarity.score("pipeline batch failed orders", "pipeline batch failed billing") > 0.0);
        // Short tokens (< 3 chars) are dropped, so "a b c" has no fingerprint.
        assertTrue(CaseSimilarity.tokens("a b c 12").isEmpty());
    }

    @Test
    void recallRanksMoreSimilarCasesHigherAndExcludesSelf() {
        CaseStore store = new CaseStore();
        Instant t = Instant.parse("2026-07-21T10:00:00Z");
        Case query = caseFor("q", "pipeline.batch.failed", "orders", "schema drift in orders feed", t);
        store.add(query);
        store.add(caseFor("near", "pipeline.batch.failed", "orders", "schema drift in orders feed", t.minusSeconds(60)));
        store.add(caseFor("far", "job.run.failed", "billing", "credential expiry", t.minusSeconds(120)));

        List<Map<String, Object>> similar = store.similar(query.symptomText(), 5, "q");
        assertTrue(similar.size() >= 1);
        assertEquals("near", similar.get(0).get("id"));           // most similar first
        assertNotNull(similar.get(0).get("similarity"));
        assertTrue(similar.stream().noneMatch(m -> "q".equals(m.get("id")))); // self excluded
        // The near case scores strictly higher than the far one (if the far one appears at all).
        if (similar.size() == 2) {
            double near = ((Number) similar.get(0).get("similarity")).doubleValue();
            double far = ((Number) similar.get(1).get("similarity")).doubleValue();
            assertTrue(near > far);
        }
    }

    @Test
    void recallIsEmptyForABlankFingerprintOrNonPositiveK() {
        CaseStore store = new CaseStore();
        store.add(caseFor("a", "pipeline.batch.failed", "orders", "drift", Instant.now()));
        assertTrue(store.similar("", 5, null).isEmpty());
        assertTrue(store.similar("pipeline", 0, null).isEmpty());
    }

    @Test
    void caseStoreIsDurableAcrossReload(@TempDir Path dir) {
        Path file = dir.resolve("agent").resolve("cases.jsonl");
        CaseStore first = new CaseStore(file);
        first.add(caseFor("c1", "pipeline.batch.failed", "orders", "schema drift", Instant.now()));
        first.add(caseFor("c2", "job.run.failed", "billing", "cred expiry", Instant.now()));

        CaseStore reloaded = new CaseStore(file);
        assertEquals(2, reloaded.size());
        Case c1 = reloaded.byId("c1").orElseThrow();
        assertEquals("incident:c1", c1.incidentRef());
        assertEquals("pipeline.batch.failed", c1.triggerSignal().get("type"));
        // Recall works over the reloaded corpus.
        List<Map<String, Object>> similar = reloaded.similar(
                caseFor("q", "pipeline.batch.failed", "orders", "schema drift", Instant.now()).symptomText(), 5, "q");
        assertEquals("c1", similar.get(0).get("id"));
    }
}
