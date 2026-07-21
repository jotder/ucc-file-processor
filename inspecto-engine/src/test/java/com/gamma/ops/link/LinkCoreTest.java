package com.gamma.ops.link;

import com.gamma.ops.ObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase-4 OBJECT_LINK value type + both stores: validation/normalisation/{@code other()}, the
 * in-memory store's newest-first incident/all, and a DuckDB round-trip — the durable correlation-graph
 * backend on the bundled engine (zero new dependency), mirroring {@code DbObjectStoreTest}.
 */
class LinkCoreTest {

    @Test
    void objectLinkValidatesNormalisesAndMaps() {
        ObjectLink l = ObjectLink.of("CASE-1", ObjectType.CASE, "INCIDENT-1", ObjectType.INCIDENT, "contains");
        assertEquals("CONTAINS", l.relationship(), "relationship is upper-cased");
        assertEquals("INCIDENT-1", l.other("CASE-1"));
        assertEquals("CASE-1", l.other("INCIDENT-1"));
        assertNull(l.other("SOMETHING-ELSE"));
        assertEquals("CASE", l.toMap().get("fromType"));
        assertEquals("INCIDENT", l.toMap().get("toType"));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectLink("", ObjectType.CASE, "x", ObjectType.INCIDENT, "r", 1), "blank from rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectLink("a", ObjectType.CASE, "b", ObjectType.INCIDENT, "  ", 1), "blank relationship rejected");
    }

    @Test
    void inMemoryAddIncidentAndAllNewestFirst() {
        InMemoryLinkStore store = new InMemoryLinkStore();
        store.add(new ObjectLink("C", ObjectType.CASE, "I1", ObjectType.INCIDENT, "CONTAINS", 100));
        store.add(new ObjectLink("C", ObjectType.CASE, "I2", ObjectType.INCIDENT, "CONTAINS", 200));
        store.add(new ObjectLink("I1", ObjectType.INCIDENT, "A1", ObjectType.ALERT, "ESCALATED_FROM", 300));

        List<ObjectLink> incidentC = store.incident("C");
        assertEquals(2, incidentC.size());
        assertEquals(200, incidentC.get(0).createdAt(), "newest-first");
        assertEquals(2, store.incident("I1").size(), "incident counts both directions");
        assertEquals(3, store.all(10).size());
        assertEquals(2, store.all(2).size(), "all respects the limit");
        assertEquals(300, store.all(10).get(0).createdAt(), "newest-first");
    }

    @Test
    void dbLinkStoreRoundTrips() throws Exception {
        try (DbLinkStore store = DbLinkStore.open("jdbc:duckdb:", null, null)) {   // in-memory database
            store.add(new ObjectLink("C", ObjectType.CASE, "I1", ObjectType.INCIDENT, "CONTAINS", 100));
            store.add(new ObjectLink("I1", ObjectType.INCIDENT, "A1", ObjectType.ALERT, "ESCALATED_FROM", 200));

            List<ObjectLink> incidentI1 = store.incident("I1");
            assertEquals(2, incidentI1.size(), "both directions");
            ObjectLink top = incidentI1.get(0);
            assertEquals(200, top.createdAt(), "newest-first");
            assertEquals(ObjectType.INCIDENT, top.fromType());
            assertEquals(ObjectType.ALERT, top.toType());
            assertEquals("ESCALATED_FROM", top.relationship());
            assertEquals(2, store.all(10).size());
        }
    }
}
