package com.gamma.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbObjectStore} over an in-memory DuckDB: INSERT/SELECT round-trips every column (incl. the
 * attribute JSON), a real mutating UPDATE persists, query filters, and the primary-key dedup. Proves
 * the durable Layer-2 backend with the bundled engine — zero new dependency.
 */
class DbObjectStoreTest {

    private DbObjectStore store;

    @BeforeEach
    void open() throws Exception {
        store = DbObjectStore.open("jdbc:duckdb:", null, null);   // in-memory database
    }

    @AfterEach
    void close() {
        store.close();
    }

    private static OperationalObject alert(String status, long created) {
        return OperationalObject.builder(ObjectType.ALERT)
                .title("t").description("d").status(status).severity("WARNING")
                .correlationId("pipe").owner("ops").attr("rule", "r1").attr("value", "0.1")
                .createdAt(created).updatedAt(created).build();
    }

    @Test
    void createGetRoundTripsAllColumns() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        OperationalObject back = store.get(o.id()).orElseThrow();
        assertEquals(o.id(), back.id());
        assertEquals(ObjectType.ALERT, back.objectType());
        assertEquals("OPEN", back.status());
        assertEquals("WARNING", back.severity());
        assertEquals("ops", back.owner(), "the quoted reserved-word column round-trips");
        assertEquals("pipe", back.correlationId());
        assertEquals("r1", back.attributes().get("rule"));
        assertEquals("0.1", back.attributes().get("value"));
        assertEquals(1000, back.createdAt());
        assertTrue(store.get("missing").isEmpty());
    }

    @Test
    void updatePersistsMutation() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        store.update(o.withStatus("RESOLVED", 2000, true));
        OperationalObject back = store.get(o.id()).orElseThrow();
        assertEquals("RESOLVED", back.status());
        assertEquals(2000, back.closedAt());
        assertEquals(2000, back.updatedAt());
    }

    @Test
    void queryFiltersAndOrders() {
        store.create(alert("OPEN", 1000));
        store.create(alert("RESOLVED", 2000));
        assertEquals(2, store.query(ObjectQuery.builder().objectType(ObjectType.ALERT).build()).size());
        assertEquals(1, store.query(ObjectQuery.builder().status("open").build()).size());
        assertEquals(2000, store.query(ObjectQuery.recent(10)).get(0).createdAt(), "newest-first");
        assertEquals(1, store.query(ObjectQuery.builder().correlationId("pipe").limit(1).build()).size());
    }

    @Test
    void deleteRemovesAndRequiresExisting() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        store.delete(o.id());
        assertTrue(store.get(o.id()).isEmpty());
        assertThrows(NoSuchElementException.class, () -> store.delete(o.id()));
        assertThrows(NoSuchElementException.class, () -> store.delete("missing"));
    }

    @Test
    void duplicateIdRejected() {
        OperationalObject o = alert("OPEN", 1000);
        store.create(o);
        assertThrows(IllegalStateException.class, () -> store.create(o));
    }
}
