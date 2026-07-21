package com.gamma.ops;

import com.gamma.ops.link.LinkRelationship;
import com.gamma.ops.link.ObjectLink;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Case group management (GLOSSARY §9 — Split &amp; Merge) at the service level: member {@code CONTAINS}
 * edges re-point, tags/watchers union on merge, trace links ({@code MERGED_INTO}/{@code SPLIT_FROM})
 * + comments record the operation, absorbed cases close, and the guard rails (non-CASE, self-merge,
 * already-merged, foreign member) throw the exceptions the routes map to 422/400/404.
 */
class ObjectServiceCaseGroupTest {

    private static OperationalObject caseOf(ObjectService svc, String title, Map<String, String> attrs) {
        return svc.open(ObjectType.CASE, title, "d", "HIGH", null, "ops", null, "corr", attrs);
    }

    private static OperationalObject incidentOf(ObjectService svc, String title) {
        return svc.open(ObjectType.INCIDENT, title, "d", "HIGH", null, null, null, "corr", Map.of());
    }

    private static List<String> containsOf(ObjectService svc, String caseId) {
        return svc.linksOf(caseId).stream()
                .filter(l -> l.fromId().equals(caseId) && LinkRelationship.CONTAINS.equalsIgnoreCase(l.relationship()))
                .map(ObjectLink::toId).sorted().toList();
    }

    @Test
    void mergeMovesMembersUnionsMetadataAndClosesSources() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject survivor = caseOf(svc, "fraud ring A", Map.of(ObjectService.ATTR_TAGS, "fraud"));
        OperationalObject source = caseOf(svc, "fraud ring A dup", Map.of(ObjectService.ATTR_TAGS, "billing,fraud"));
        svc.watch(source.id(), "carol");
        OperationalObject i1 = incidentOf(svc, "one");
        OperationalObject i2 = incidentOf(svc, "two");
        OperationalObject i3 = incidentOf(svc, "three");
        svc.link(source.id(), i1.id(), LinkRelationship.CONTAINS, null);
        svc.link(source.id(), i2.id(), LinkRelationship.CONTAINS, null);
        svc.link(survivor.id(), i3.id(), LinkRelationship.CONTAINS, null);

        ObjectService.MergeResult result = svc.mergeCases(survivor.id(), List.of(source.id()), "op");

        assertEquals(2, result.membersMoved());
        assertEquals(List.of(source.id()), result.merged());
        assertEquals(List.of(i1.id(), i2.id(), i3.id()).stream().sorted().toList(),
                containsOf(svc, survivor.id()), "all members now contained by the survivor");
        assertTrue(containsOf(svc, source.id()).isEmpty(), "the absorbed case keeps no members");

        // metadata union onto the survivor
        String tags = result.survivor().attributes().get(ObjectService.ATTR_TAGS);
        assertTrue(tags.contains("fraud") && tags.contains("billing"), "tags union: " + tags);
        assertTrue(result.survivor().watchers().contains("carol"), "watchers union");

        // the absorbed case is closed with the trace marker + link; history stays reachable
        OperationalObject closed = svc.get(source.id()).orElseThrow();
        assertEquals("CLOSED", closed.status());
        assertTrue(closed.isClosed());
        assertEquals(survivor.id(), closed.attributes().get(ObjectService.ATTR_MERGED_INTO));
        assertTrue(svc.linksOf(source.id()).stream().anyMatch(l ->
                        LinkRelationship.MERGED_INTO.equals(l.relationship()) && l.toId().equals(survivor.id())),
                "MERGED_INTO trace link");

        // guard rails
        assertThrows(IllegalStateException.class, () -> svc.mergeCases(survivor.id(), List.of(source.id()), null),
                "an already-merged case cannot be merged again");
        assertThrows(IllegalStateException.class, () -> svc.mergeCases(survivor.id(), List.of(survivor.id()), null),
                "self-merge");
        assertThrows(IllegalStateException.class, () -> svc.mergeCases(survivor.id(), List.of(i3.id()), null),
                "only CASEs merge");
        assertThrows(IllegalArgumentException.class, () -> svc.mergeCases(survivor.id(), List.of(), null));
        assertThrows(NoSuchElementException.class, () -> svc.mergeCases("nope", List.of(source.id()), null));
    }

    @Test
    void splitCarvesMembersIntoANewCaseAndKeepsTheRest() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject original = caseOf(svc, "big investigation",
                Map.of("category", "Security / Data / Leak suspected", ObjectService.ATTR_TAGS, "urgent"));
        OperationalObject i1 = incidentOf(svc, "one");
        OperationalObject i2 = incidentOf(svc, "two");
        OperationalObject i3 = incidentOf(svc, "three");
        for (OperationalObject i : List.of(i1, i2, i3))
            svc.link(original.id(), i.id(), LinkRelationship.CONTAINS, null);

        ObjectService.SplitResult result = svc.splitCase(original.id(), "part B",
                List.of(i1.id(), i2.id()), "dana", null, "op");

        assertEquals(2, result.membersMoved());
        OperationalObject part = result.part();
        assertEquals(ObjectType.CASE, part.objectType());
        assertEquals("part B", part.title());
        assertEquals("dana", part.assignee(), "optional assignee routes the new part");
        assertEquals("Security / Data / Leak suspected", part.attributes().get("category"), "category inherited");
        assertTrue(part.attributes().get(ObjectService.ATTR_TAGS).contains("urgent"), "tags inherited");
        assertEquals(List.of(i1.id(), i2.id()).stream().sorted().toList(), containsOf(svc, part.id()));
        assertEquals(List.of(i3.id()), containsOf(svc, original.id()), "the original keeps the rest");
        assertTrue(svc.linksOf(part.id()).stream().anyMatch(l ->
                        LinkRelationship.SPLIT_FROM.equals(l.relationship()) && l.toId().equals(original.id())),
                "SPLIT_FROM trace link");

        // guard rails
        assertThrows(IllegalStateException.class,
                () -> svc.splitCase(original.id(), "x", List.of(i1.id()), null, null, null),
                "a member already moved out is foreign now");
        assertThrows(IllegalArgumentException.class,
                () -> svc.splitCase(original.id(), " ", List.of(i3.id()), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> svc.splitCase(original.id(), "x", List.of(), null, null, null));
        assertThrows(IllegalStateException.class,
                () -> svc.splitCase(i3.id(), "x", List.of(i3.id()), null, null, null), "only CASEs split");
    }

    @Test
    void unlinkRemovesTheEdgeOnceAndReports() {
        ObjectService svc = new ObjectService(new InMemoryObjectStore());
        OperationalObject c = caseOf(svc, "c", Map.of());
        OperationalObject i = incidentOf(svc, "i");
        svc.link(c.id(), i.id(), LinkRelationship.CONTAINS, null);

        assertTrue(svc.unlink(c.id(), i.id(), LinkRelationship.CONTAINS, "op"));
        assertTrue(containsOf(svc, c.id()).isEmpty());
        assertFalse(svc.unlink(c.id(), i.id(), LinkRelationship.CONTAINS, "op"), "second removal is a no-op");
        assertThrows(NoSuchElementException.class, () -> svc.unlink("nope", i.id(), LinkRelationship.CONTAINS, null));
    }
}
