package com.gamma.ops.link;

import com.gamma.ops.ObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DbLinkStore} over an in-memory DuckDB: add/incident round-trip and the case-group
 * {@link LinkStore#remove} (case-insensitive relationship match, {@code false} on a miss) — proving
 * the contract amendment on the durable backend, not just the in-memory default.
 */
class DbLinkStoreTest {

    private DbLinkStore store;

    @BeforeEach
    void open() throws Exception {
        store = DbLinkStore.open("jdbc:duckdb:", null, null);   // in-memory database
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void addRemoveRoundTrip() {
        store.add(ObjectLink.of("case-1", ObjectType.CASE, "inc-1", ObjectType.INCIDENT, LinkRelationship.CONTAINS));
        store.add(ObjectLink.of("case-1", ObjectType.CASE, "inc-2", ObjectType.INCIDENT, LinkRelationship.CONTAINS));
        assertEquals(2, store.incident("case-1").size());

        assertTrue(store.remove("case-1", "inc-1", "contains"), "relationship matches case-insensitively");
        assertEquals(1, store.incident("case-1").size());
        assertEquals("inc-2", store.incident("case-1").get(0).toId());

        assertFalse(store.remove("case-1", "inc-1", LinkRelationship.CONTAINS), "second removal is a no-op");
        assertFalse(store.remove("case-1", "inc-2", LinkRelationship.MERGED_INTO), "relationship must match");
        assertTrue(store.remove("case-1", "inc-2", LinkRelationship.CONTAINS));
        assertTrue(store.incident("case-1").isEmpty());
    }
}
