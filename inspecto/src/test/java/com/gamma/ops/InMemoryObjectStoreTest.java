package com.gamma.ops;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/** {@link InMemoryObjectStore}: create/get, duplicate + missing guards, mutating update, filter/sort/page. */
class InMemoryObjectStoreTest {

    private static OperationalObject obj(ObjectType type, String status, long created) {
        return OperationalObject.builder(type).status(status).severity("INFO")
                .createdAt(created).updatedAt(created).build();
    }

    @Test
    void createGetAndRejectDuplicate() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        OperationalObject o = obj(ObjectType.ALERT, "OPEN", 100);
        store.create(o);
        assertEquals(o.id(), store.get(o.id()).orElseThrow().id());
        assertTrue(store.get("missing").isEmpty());
        assertThrows(IllegalStateException.class, () -> store.create(o));
    }

    @Test
    void updateMutatesAndRequiresExisting() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        OperationalObject o = obj(ObjectType.ALERT, "OPEN", 100);
        store.create(o);
        store.update(o.withStatus("ACKNOWLEDGED", 200, false));
        assertEquals("ACKNOWLEDGED", store.get(o.id()).orElseThrow().status());
        assertThrows(NoSuchElementException.class, () -> store.update(obj(ObjectType.ALERT, "OPEN", 100)));
    }

    @Test
    void queryFiltersSortsNewestFirstAndPages() {
        InMemoryObjectStore store = new InMemoryObjectStore();
        store.create(obj(ObjectType.ALERT, "OPEN", 100));
        store.create(obj(ObjectType.ALERT, "RESOLVED", 200));
        store.create(obj(ObjectType.ISSUE, "OPEN", 300));

        List<OperationalObject> alerts = store.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build());
        assertEquals(2, alerts.size());
        assertEquals(200, alerts.get(0).createdAt(), "newest-first");
        assertEquals(1, store.query(ObjectQuery.builder().objectType(ObjectType.ALERT).status("OPEN").build()).size());
        assertEquals(3, store.size());

        assertEquals(2, store.query(ObjectQuery.builder().limit(2).build()).size());
        assertEquals(1, store.query(ObjectQuery.builder().limit(2).offset(2).build()).size(), "offset pages");
    }
}
