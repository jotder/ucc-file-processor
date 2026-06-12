package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the YAML-list silent-fallthrough bug.
 *
 * <p>Before C4, a {@code partitions:} key parsed by JToon as a Map (because the
 * author used YAML-style {@code - key: value} list items) silently fell through
 * to the empty-list branch in {@link PartitionDef#fromSchema}.  Every row then
 * landed in the {@code year=1900/month=01/day=01} sentinel partition — manifesting
 * as a single output file regardless of distinct date values.  The fix makes this
 * an immediate, loud failure at config load.
 */
class PartitionDefHardFailTest {

    @Test
    void throwsWhenPartitionsIsAMap() {
        Map<String, Object> schema = new LinkedHashMap<>();
        // Simulate what JToon produces when given YAML-style "- key: value"
        // list items — a Map, not a List.
        schema.put("partitions", Map.of("column", "year"));

        var e = assertThrows(IllegalArgumentException.class,
                () -> PartitionDef.fromSchema(schema));
        assertTrue(e.getMessage().contains("partitions[N]"),
                "Error must point operators at the correct JToon syntax. Got: " + e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("map"),
                "Error should name the actual type encountered (a Map). Got: " + e.getMessage());
    }

    @Test
    void emptyListStillFallsThroughToLegacyOrSentinel() {
        // An empty list legitimately means "no explicit partitions[] declared,
        // try the legacy partitionKey or fall back to the sentinel."
        Map<String, Object> schema = Map.of("partitions", java.util.List.of());
        assertTrue(PartitionDef.fromSchema(schema).isEmpty());
    }

    @Test
    void absentPartitionsKeyFallsThroughToLegacyOrSentinel() {
        assertTrue(PartitionDef.fromSchema(Map.of()).isEmpty());
    }

    @Test
    void validPartitionsListParsesCleanly() {
        Map<String, Object> schema = Map.of("partitions", java.util.List.of(
                Map.of("column", "event_type", "source", "EVENT_TYPE", "type", "VARCHAR"),
                Map.of("column", "year", "source", "EVENT_DATE", "type", "DATE_YEAR")));
        var defs = PartitionDef.fromSchema(schema);
        assertEquals(2, defs.size());
        assertEquals("event_type", defs.get(0).column());
        assertEquals(PartitionDef.Type.DATE_YEAR, defs.get(1).type());
    }
}
