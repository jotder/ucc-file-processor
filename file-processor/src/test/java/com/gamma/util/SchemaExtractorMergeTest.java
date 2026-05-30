package com.gamma.util;

import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SchemaExtractor}'s description merge/preserve (P1): regeneration must never
 * clobber operator-authored prose, must match by column name (not index), and must keep the
 * schema {@code .toon} backward-compatible (3 columns) when nothing is preserved — while staying
 * header-uniform (4+ columns) when something is. Includes a JToon round-trip proving the enriched
 * tabular array encodes and decodes intact.
 */
class SchemaExtractorMergeTest {

    private static Map<String, String> field(String name, String selector, String type) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("selector", selector);
        f.put("type", type);
        return f;
    }

    private static List<Map<String, String>> fresh(String... names) {
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) out.add(field(names[i], String.valueOf(i), "VARCHAR"));
        return out;
    }

    @Test
    void noExistingDescriptionsLeavesThreeColumns() {
        List<Map<String, String>> fields = fresh("A", "B");
        SchemaExtractor.mergeDescriptions(fields, Map.of());
        for (Map<String, String> f : fields) {
            assertEquals(3, f.size(), "no preserved prose -> unchanged 3-column field map");
            assertFalse(f.containsKey("description"));
        }
    }

    @Test
    void preservesAuthoredProseByNameAndStaysHeaderUniform() {
        List<Map<String, String>> fields = fresh("CALL_DURATION", "MSISDN");
        Map<String, Map<String, String>> existing = Map.of(
                "CALL_DURATION", Map.of("description", "Billed call length in seconds"));

        SchemaExtractor.mergeDescriptions(fields, existing);

        // matched column carries the authored prose
        assertEquals("Billed call length in seconds", fields.get(0).get("description"));
        // every field has the key (header-uniform), empty where not authored
        assertTrue(fields.get(1).containsKey("description"));
        assertEquals("", fields.get(1).get("description"));
        // key order stays name, selector, type, description
        assertEquals(List.of("name", "selector", "type", "description"),
                new ArrayList<>(fields.get(0).keySet()));
    }

    @Test
    void dropsProseForRemovedColumnKeepsSurvivors() {
        // A and B authored; regeneration yields A and C (B removed/renamed, C new)
        List<Map<String, String>> fields = fresh("A", "C");
        Map<String, Map<String, String>> existing = new LinkedHashMap<>();
        existing.put("A", Map.of("description", "alpha"));
        existing.put("B", Map.of("description", "beta — should be dropped"));

        SchemaExtractor.mergeDescriptions(fields, existing);

        assertEquals("alpha", fields.get(0).get("description"), "survivor keeps its prose");
        assertEquals("", fields.get(1).get("description"), "new column seeded empty");
        assertEquals(2, fields.size(), "removed column does not reappear");
        assertFalse(fields.stream().anyMatch(f -> "beta — should be dropped".equals(f.get("description"))));
    }

    @Test
    void carriesUnitAndClassificationToo() {
        List<Map<String, String>> fields = fresh("AMOUNT", "STAMP");
        Map<String, Map<String, String>> existing = Map.of(
                "AMOUNT", Map.of("description", "Recharge amount", "unit", "USD", "classification", "PII"));

        SchemaExtractor.mergeDescriptions(fields, existing);

        assertEquals("USD", fields.get(0).get("unit"));
        assertEquals("PII", fields.get(0).get("classification"));
        // every field gets every active key, in stable order description, unit, classification
        assertEquals(List.of("name", "selector", "type", "description", "unit", "classification"),
                new ArrayList<>(fields.get(1).keySet()));
        assertEquals("", fields.get(1).get("unit"));
    }

    @Test
    void readExistingReturnsEmptyForMissingOrMalformed(@TempDir Path dir) throws Exception {
        assertTrue(SchemaExtractor.readExistingDescriptions(
                dir.resolve("nope_schema.toon").toString()).isEmpty());

        Path junk = dir.resolve("junk_schema.toon");
        Files.writeString(junk, "this is : not : valid toon ::::", StandardCharsets.UTF_8);
        // never throws; worst case an empty map
        assertNotNull(SchemaExtractor.readExistingDescriptions(junk.toString()));
    }

    @Test
    void roundTripThroughJToonPreservesEnrichedSchema(@TempDir Path dir) throws Exception {
        // Build a schema with a 4-column fields array (one authored, one empty) and write it.
        List<Map<String, String>> fields = fresh("CALL_DURATION", "MSISDN");
        SchemaExtractor.mergeDescriptions(fields, Map.of(
                "CALL_DURATION", Map.of("description", "Billed call length in seconds")));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "EVENTS");
        raw.put("format", "CSV");
        raw.put("fields", fields);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("raw", raw);

        Path file = dir.resolve("events_schema.toon");
        Files.writeString(file, JToon.encode(schema), StandardCharsets.UTF_8);

        // Read back via the extractor's own reader -> authored prose survives encode+decode.
        Map<String, Map<String, String>> back = SchemaExtractor.readExistingDescriptions(file.toString());
        assertEquals("Billed call length in seconds", back.get("CALL_DURATION").get("description"));
        // the empty description on MSISDN is not "authored", so it is not carried (blank filtered)
        assertFalse(back.containsKey("MSISDN"));

        // And a second regeneration cycle preserves it (idempotent merge).
        List<Map<String, String>> regen = fresh("CALL_DURATION", "MSISDN");
        SchemaExtractor.mergeDescriptions(regen, back);
        assertEquals("Billed call length in seconds", regen.get(0).get("description"));
    }
}
