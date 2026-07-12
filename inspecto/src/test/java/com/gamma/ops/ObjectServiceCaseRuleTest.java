package com.gamma.ops;

import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.ObjectLink;
import com.gamma.ops.tag.CaseRule;
import com.gamma.ops.tag.TagRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule-raised cases (GLOSSARY §9, C5): {@link ObjectService#evaluateCaseRule} groups matching
 * in-window Incidents under a Case once the threshold is met, attaches later matches to the same
 * open Case (idempotent), and skips Incidents already grouped. Also covers case analytics (C4).
 */
class ObjectServiceCaseRuleTest {

    private static OperationalObject incident(ObjectService svc, String title, String priority) {
        return svc.open(ObjectType.INCIDENT, title, "d", "HIGH", priority, null, null, "corr", Map.of());
    }

    private static List<String> membersOf(ObjectService svc, String caseId) {
        return svc.linksOf(caseId).stream()
                .filter(l -> l.fromId().equals(caseId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()))
                .map(ObjectLink::toId).sorted().toList();
    }

    private static CaseRule rule(int threshold) {
        return new CaseRule("crit-cluster", "Critical incident cluster",
                new TagRule.Filter("INCIDENT", null, null, "CRITICAL", null, null),
                threshold, 1440, "Pipeline / Ingest", "auto", 1);
    }

    @Test
    void belowThresholdRaisesNothingThenGroupsWhenMet() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerCaseRule(rule(3));
        incident(svc, "one", "CRITICAL");
        incident(svc, "two", "CRITICAL");
        incident(svc, "low", "LOW");   // doesn't match the filter

        ObjectService.CaseRuleEvaluation below = svc.evaluateCaseRule("crit-cluster");
        assertEquals(2, below.matched());
        assertEquals(0, below.grouped(), "2 < threshold 3 — no case yet");
        assertNull(below.caseId());

        incident(svc, "three", "CRITICAL");
        ObjectService.CaseRuleEvaluation met = svc.evaluateCaseRule("crit-cluster");
        assertEquals(3, met.matched());
        assertEquals(3, met.grouped());
        assertTrue(met.opened());
        OperationalObject raised = svc.get(met.caseId()).orElseThrow();
        assertEquals(ObjectType.CASE, raised.objectType());
        assertEquals("crit-cluster", raised.attributes().get(ObjectService.ATTR_RAISED_BY_RULE));
        assertEquals("Pipeline / Ingest", raised.attributes().get("category"), "inherits the rule's category");
        assertEquals("auto", raised.attributes().get(ObjectService.ATTR_TAGS));
        assertEquals(3, membersOf(svc, met.caseId()).size());
    }

    @Test
    void reEvaluationAttachesNewMatchesToTheSameOpenCaseAndIsIdempotent() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        svc.registerCaseRule(rule(2));
        incident(svc, "a", "CRITICAL");
        incident(svc, "b", "CRITICAL");
        String caseId = svc.evaluateCaseRule("crit-cluster").caseId();
        assertNotNull(caseId);

        // no new matches → idempotent no-op (already-grouped incidents are skipped)
        ObjectService.CaseRuleEvaluation again = svc.evaluateCaseRule("crit-cluster");
        assertEquals(0, again.matched());

        // a fresh matching incident attaches to the SAME still-open case (not a second one)
        incident(svc, "c", "CRITICAL");
        ObjectService.CaseRuleEvaluation attach = svc.evaluateCaseRule("crit-cluster");
        assertEquals(caseId, attach.caseId());
        assertFalse(attach.opened(), "attached to the existing rule-raised case");
        assertEquals(3, membersOf(svc, caseId).size());
        assertEquals(1, svc.query(ObjectQuery.builder().objectType(ObjectType.CASE).build()).size(),
                "exactly one case was raised");

        assertThrows(NoSuchElementException.class, () -> svc.evaluateCaseRule("ghost"));
    }

    @Test
    void windowExcludesOldIncidents() {
        // Seed a back-dated incident straight into the store (open() would stamp "now").
        InMemoryObjectStore store = new InMemoryObjectStore();
        long twoHoursAgo = System.currentTimeMillis() - 2 * 3_600_000L;
        store.create(OperationalObject.builder(ObjectType.INCIDENT)
                .title("stale").description("d").status("IDENTIFIED").priority("CRITICAL")
                .createdAt(twoHoursAgo).updatedAt(twoHoursAgo).build());
        ObjectService svc = new ObjectService(store);
        svc.registerCaseRule(new CaseRule("recent", "Recent cluster",
                new TagRule.Filter("INCIDENT", null, null, "CRITICAL", null, null), 1, 60, null, null, 1));
        incident(svc, "fresh", "CRITICAL");   // created now — inside the 60-minute window

        ObjectService.CaseRuleEvaluation r = svc.evaluateCaseRule("recent");
        assertEquals(1, r.matched(), "only the in-window incident matched; the 2h-old one is excluded");
    }

    @Test
    void analyticsRollsUpCountsCycleTimeAndImpact() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c1 = svc.open(ObjectType.CASE, "one", "d", "HIGH", "MAJOR", null, null, "corr",
                Map.of("category", "Security / Data / Leak", "impactAmount", "1000", "recordsAffected", "50"));
        svc.open(ObjectType.CASE, "two", "d", "HIGH", "LOW", null, null, "corr",
                Map.of("category", "Pipeline / Ingest / Parse", "impactAmount", "500"));
        // resolve+close c1 so it contributes a cycle time
        svc.transition(c1.id(), "investigate", "op");
        svc.transition(c1.id(), "resolve", "op");
        svc.transition(c1.id(), "close", "op");

        Map<String, Object> a = svc.analytics(ObjectType.CASE);
        assertEquals(2, a.get("total"));
        assertEquals(1, a.get("backlog"), "the still-open case");
        @SuppressWarnings("unchecked")
        Map<String, Integer> byCategory = (Map<String, Integer>) a.get("byCategory");
        assertEquals(1, byCategory.get("Security"));
        assertEquals(1, byCategory.get("Pipeline"));
        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) a.get("impact");
        assertEquals(1500.0, (double) impact.get("impactAmount"), 0.001);
        assertEquals(50L, impact.get("recordsAffected"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cycle = (Map<String, Object>) a.get("cycleTime");
        assertEquals(1, cycle.get("count"), "one closed case contributes a cycle time");
    }
}
