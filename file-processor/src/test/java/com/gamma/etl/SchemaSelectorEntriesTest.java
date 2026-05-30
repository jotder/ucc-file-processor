package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the read-only {@link SchemaSelector#entries()} catalog accessor (P2). */
class SchemaSelectorEntriesTest {

    @Test
    void entriesReturnsEverySchemaWithTableInPriorityOrder() {
        LinkedHashMap<Integer, Map<String, Object>> byCount = new LinkedHashMap<>();
        LinkedHashMap<Integer, PathMatcher> byPattern = new LinkedHashMap<>();
        LinkedHashMap<Integer, String> byTable = new LinkedHashMap<>();

        Map<String, Object> schema3 = Map.of("raw", Map.of("name", "WIDE"));
        Map<String, Object> schema2 = Map.of("raw", Map.of("name", "NARROW"));
        SchemaSelector.register(byCount, byPattern, byTable, 116, null, schema3, "wide_tbl");
        SchemaSelector.register(byCount, byPattern, byTable, 76, null, schema2, "narrow_tbl");

        SchemaSelector sel = new SchemaSelector(byCount, byPattern, byTable, ",", 0);
        List<SchemaSelector.Selection> entries = sel.entries();

        assertEquals(2, entries.size());
        // insertion (priority) order preserved
        assertEquals("wide_tbl", entries.get(0).table());
        assertSame(schema3, entries.get(0).schema());
        assertEquals("narrow_tbl", entries.get(1).table());
        assertSame(schema2, entries.get(1).schema());
    }

    @Test
    void emptySelectorHasNoEntries() {
        SchemaSelector sel = new SchemaSelector(
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), ",", 0);
        assertFalse(sel.hasSchemas());
        assertTrue(sel.entries().isEmpty());
    }
}
