package com.gamma.catalog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for {@link SchemaProjection} (P2): column projection + provenance + table naming. */
class SchemaProjectionTest {

    private static Map<String, String> field(String name, String selector, String type,
                                              String description, String unit, String classification) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("selector", selector);
        f.put("type", type);
        if (description != null) f.put("description", description);
        if (unit != null) f.put("unit", unit);
        if (classification != null) f.put("classification", classification);
        return f;
    }

    private static Map<String, Object> schema(String name, String canonical, List<Map<String, String>> fields) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", name);
        raw.put("fields", fields);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("raw", raw);
        if (canonical != null) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("canonicalName", canonical);
            schema.put("mapping", mapping);
        }
        return schema;
    }

    @Test
    void projectsColumnsWithProvenanceAndDomainMeta() {
        List<Map<String, String>> fields = new ArrayList<>();
        fields.add(field("DURATION", "2", "DOUBLE", "Billed call length in seconds", "s", "INTERNAL"));
        fields.add(field("MSISDN", "0", "VARCHAR", "", null, null)); // blank description

        List<SchemaProjection.Column> cols = SchemaProjection.columns(schema("CALL", "call_events", fields));
        assertEquals(2, cols.size());

        SchemaProjection.Column duration = cols.get(0);
        assertEquals("DURATION", duration.name());
        assertEquals("DOUBLE", duration.type());
        assertEquals("Billed call length in seconds", duration.description().text());
        assertEquals(Provenance.MANUAL, duration.description().provenance(),
                "non-blank schema-file prose is treated as operator-authored (MANUAL)");
        assertEquals("s", duration.unit());
        assertEquals("INTERNAL", duration.classification());

        SchemaProjection.Column msisdn = cols.get(1);
        assertEquals(Description.EMPTY, msisdn.description(), "blank description -> EMPTY/NONE");
        assertEquals("", msisdn.unit());
    }

    @Test
    void threeColumnSchemaProjectsEmptyDescriptions() {
        List<Map<String, String>> fields = new ArrayList<>();
        fields.add(field("A", "0", "VARCHAR", null, null, null)); // no description key at all
        fields.add(field("B", "1", "DOUBLE", null, null, null));

        List<SchemaProjection.Column> cols = SchemaProjection.columns(schema("T", null, fields));
        assertEquals(2, cols.size());
        assertTrue(cols.stream().allMatch(c -> c.description() == Description.EMPTY
                || !c.description().isPresent()));
    }

    @Test
    void canonicalNameFallsBackThroughMappingThenRaw() {
        assertEquals("call_events",
                SchemaProjection.canonicalName(schema("CALL", "call_events", List.of())));
        assertEquals("CALL", SchemaProjection.canonicalName(schema("CALL", null, List.of())));
        assertNull(SchemaProjection.canonicalName(null));
        assertNull(SchemaProjection.canonicalName(Map.of()));
    }

    @Test
    void malformedSchemaYieldsNoColumns() {
        assertTrue(SchemaProjection.columns(null).isEmpty());
        assertTrue(SchemaProjection.columns(Map.of()).isEmpty());
        assertTrue(SchemaProjection.columns(Map.of("raw", "not-a-map")).isEmpty());
    }
}
