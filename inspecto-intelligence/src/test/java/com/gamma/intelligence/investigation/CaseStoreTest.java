package com.gamma.intelligence.investigation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CaseStoreTest {

    private static Case sample(String id) {
        return new Case(id, "incident:" + id, Map.of("type", "pipeline.batch.failed"),
                List.of(), List.of(), "open", List.of(), Instant.now());
    }

    @Test
    void recentReturnsNewestFirstCappedAtLimit() {
        CaseStore store = new CaseStore();
        store.add(sample("c1"));
        store.add(sample("c2"));
        store.add(sample("c3"));

        List<Case> recent = store.recent(2);
        assertEquals(2, recent.size());
        assertEquals("c3", recent.get(0).id());
        assertEquals("c2", recent.get(1).id());
    }

    @Test
    void evictsOldestWhenOverCapacity() {
        CaseStore store = new CaseStore(2);
        store.add(sample("c1"));
        store.add(sample("c2"));
        store.add(sample("c3"));

        assertEquals(2, store.size());
        assertTrue(store.byId("c1").isEmpty(), "the oldest case is evicted");
        assertTrue(store.byId("c3").isPresent());
    }

    @Test
    void byIdReturnsEmptyForUnknownId() {
        CaseStore store = new CaseStore();
        store.add(sample("c1"));
        assertTrue(store.byId("nope").isEmpty());
    }

    @Test
    void toViewExposesEveryField() {
        Map<String, Object> view = sample("c1").toView();
        assertEquals("c1", view.get("id"));
        assertEquals("incident:c1", view.get("incidentRef"));
        assertEquals("open", view.get("outcome"));
        assertNotNull(view.get("createdAt"));
    }
}
