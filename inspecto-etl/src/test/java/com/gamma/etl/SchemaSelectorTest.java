package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SchemaSelector} — the two-pass schema dispatch used by
 * multi-schema pipelines (file-pattern fast path, then column-count probe).
 * Previously 0% covered despite being load-bearing for multi-schema sources.
 */
class SchemaSelectorTest {

    private static Map<String, Object> schema(String name) {
        return Map.of("raw", Map.of("name", name, "fields", List.of(
                Map.of("name", "C0", "selector", "0", "type", "VARCHAR"))));
    }

    /** Build a selector with two schemas: a 3-col (pattern-tagged) and a 5-col. */
    private static SchemaSelector twoSchema() {
        var byCount   = new LinkedHashMap<Integer, Map<String, Object>>();
        var byPattern = new LinkedHashMap<Integer, PathMatcher>();
        var byTable   = new LinkedHashMap<Integer, String>();
        SchemaSelector.register(byCount, byPattern, byTable, 3, "glob:**/*.special.csv", schema("a"), "table_a");
        SchemaSelector.register(byCount, byPattern, byTable, 5, null, schema("b"), "table_b");
        return new SchemaSelector(byCount, byPattern, byTable, ",", 0);
    }

    /** Pass 1: file-pattern match wins regardless of the file's actual column count. */
    @Test
    void filePatternFastPathMatches(@TempDir Path dir) throws Exception {
        File f = dir.resolve("feed.special.csv").toFile();
        // 5 columns physically, but the pattern points it at table_a (the 3-col schema)
        Files.writeString(f.toPath(), "a,b,c,d,e\n");
        SchemaSelector.Selection sel = twoSchema().select(f);
        assertEquals("table_a", sel.table(), "pattern fast path should win over column count");
    }

    /** Pass 2: no pattern match → column-count probe selects by max column count. */
    @Test
    void columnCountProbeSelects(@TempDir Path dir) throws Exception {
        File f = dir.resolve("plain.csv").toFile();
        Files.writeString(f.toPath(), "a,b,c,d,e\n1,2,3,4,5\n");   // 5 columns
        SchemaSelector.Selection sel = twoSchema().select(f);
        assertEquals("table_b", sel.table(), "5-column file should map to the 5-col schema");
    }

    /** Probe takes the MAX column count over scanned lines — banner lines with
     *  fewer columns must not mislead it. */
    @Test
    void probeUsesMaxColumnCountAcrossLines(@TempDir Path dir) throws Exception {
        File f = dir.resolve("banner.csv").toFile();
        // A short banner line first, then real 5-column data.
        Files.writeString(f.toPath(), "Oracle banner\nConnected\na,b,c,d,e\n1,2,3,4,5\n");
        SchemaSelector.Selection sel = twoSchema().select(f);
        assertEquals("table_b", sel.table(), "max column count (5) should drive selection");
    }

    /** No pattern and an unconfigured column count → IllegalStateException. */
    @Test
    void noMatchThrows(@TempDir Path dir) throws Exception {
        File f = dir.resolve("weird.csv").toFile();
        Files.writeString(f.toPath(), "a,b,c,d,e,f,g\n");   // 7 columns — not configured
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> twoSchema().select(f));
        assertTrue(ex.getMessage().contains("No schema matched"), ex.getMessage());
    }

    /** The probe is GZip-transparent. */
    @Test
    void probeHandlesGzip(@TempDir Path dir) throws Exception {
        File f = dir.resolve("data.csv.gz").toFile();
        try (var gz = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(f))) {
            gz.write("a,b,c,d,e\n1,2,3,4,5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        SchemaSelector.Selection sel = twoSchema().select(f);
        assertEquals("table_b", sel.table(), "gzip probe should read 5 columns");
    }

    @Test
    void hasSchemasReflectsRegistration() {
        var empty = new SchemaSelector(new LinkedHashMap<>(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), ",", 0);
        assertFalse(empty.hasSchemas());
        assertTrue(twoSchema().hasSchemas());
    }
}
