package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the unified {@code parsing:} block (docs/parsing-options-reference.md §5/§8.5):
 * {@code parsing.delimited} aliases the legacy {@code processing.csv_settings},
 * {@code parsing.plugin} aliases {@code processing.ingester}/{@code segments}/{@code ingester_config},
 * and a config with no {@code parsing:} block parses exactly as before.
 */
class UnifiedParsingBlockTest {

    private static final String SCHEMA = """
            partitionKey: TXN_DATE
            raw:
              name: t
              format: CSV
              fields[1]{name,selector,type}:
                ID,"0",VARCHAR
            mapping:
              canonicalName: t
              rawName: t
              rules[1]{targetColumn,sourceExpression,transformType}:
                ID,ID,DIRECT
            """;

    @Test
    void parsingDelimitedAliasesCsvSettings(@TempDir Path dir) throws Exception {
        // Legacy spelling …
        PipelineConfig legacy = load(dir, "old", """
                  csv_settings:
                    delimiter: "|"
                    has_header: false
                    skip_header_lines: 2
                    engine: java
                """, "");
        // … and the unified block must produce the identical CsvSettings.
        PipelineConfig unified = load(dir, "new", "", """
                parsing:
                  frontend: delimited
                  delimited:
                    delimiter: "|"
                    has_header: false
                    skip_header_lines: 2
                    engine: java
                """);
        assertEquals(legacy.csv(), unified.csv(), "parsing.delimited == csv_settings, key for key");
        assertNull(unified.fixedWidth());
        assertNull(unified.json());
        assertNull(unified.textRegex());
    }

    @Test
    void parsingKeysOverrideLegacyCsvSettings(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "ovr", """
                  csv_settings:
                    delimiter: ","
                    skip_header_lines: 5
                """, """
                parsing:
                  frontend: delimited
                  delimited:
                    delimiter: "|"
                """);
        assertEquals("|", cfg.csv().delimiter(), "parsing.delimited wins over csv_settings");
        assertEquals(5, cfg.csv().skipHeaderLines(), "untouched legacy keys survive the overlay");
    }

    @Test
    void sharedEncodingAndCompressionOptionsApply(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "enc", "", """
                parsing:
                  frontend: delimited
                  encoding: latin-1
                  compression: gzip
                """);
        assertEquals("latin-1", cfg.csv().encoding());
        assertEquals("gzip", cfg.csv().inputCompression());
    }

    @Test
    void noParsingBlockIsBehaviourPreserving(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = load(dir, "leg", """
                  csv_settings:
                    delimiter: ","
                    engine: auto
                """, "");
        assertEquals(",", cfg.csv().delimiter());
        assertTrue(cfg.csv().hasHeader(), "delimited default has_header=true untouched");
        assertNull(cfg.json());
        assertNull(cfg.textRegex());
        assertNull(cfg.fixedWidth());
    }

    @Test
    void parsingPluginAliasesProcessingIngester(@TempDir Path dir) throws Exception {
        Path seg = dir.resolve("seg_main.toon");
        Files.writeString(seg, SCHEMA, StandardCharsets.UTF_8);
        PipelineConfig cfg = load(dir, "plg", "", """
                parsing:
                  frontend: plugin
                  plugin:
                    ingester: com.gamma.ingester.FixedWidthRecordIngester
                    segments:
                      MAIN: %s
                    ingester_config:
                      record_length: 24
                """.formatted(seg.toString().replace('\\', '/')));
        assertEquals("com.gamma.ingester.FixedWidthRecordIngester", cfg.schemas().ingesterClass());
        assertEquals(java.util.Set.of("MAIN"), cfg.schemas().segments().keySet());
        assertEquals("24", String.valueOf(cfg.schemas().ingesterConfig().get("record_length")));
    }

    @Test
    void pluginFrontendWithoutIngesterFailsLoad(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "nip", "", "parsing:\n  frontend: plugin\n"));
        assertTrue(e.getMessage().contains("plugin"), e.getMessage());
    }

    @Test
    void unknownFrontendInCsvSettingsAlsoRejected(@TempDir Path dir) {
        Exception e = assertThrows(IllegalArgumentException.class,
                () -> load(dir, "ufc", "  csv_settings:\n    frontend: florble\n", ""));
        assertTrue(e.getMessage().contains("Unknown parsing.frontend"), e.getMessage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String fwd(Path p) { return p.toString().replace('\\', '/'); }

    /**
     * Build + load a pipeline; {@code procExtra} is appended inside {@code processing:} (already
     * indented two spaces), {@code topExtra} at the top level (e.g. the {@code parsing:} block).
     */
    private static PipelineConfig load(Path dir, String tag, String procExtra, String topExtra)
            throws Exception {
        Path schema = dir.resolve("schema_" + tag + ".toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        String d = fwd(dir);
        String pipe =
                "name: UP_" + tag + "\n" +
                "version: 1\n" +
                "dirs:\n" +
                "  poll: " + d + "/inbox\n" +
                "  database: " + d + "/db\n" +
                "  backup: " + d + "/backup\n" +
                "  temp: " + d + "/temp\n" +
                "  errors: " + d + "/errors\n" +
                "  quarantine: " + d + "/quarantine\n" +
                "  status_dir: " + d + "/status\n" +
                "output:\n" +
                "  format: CSV\n" +
                "processing:\n" +
                "  threads: 1\n" +
                "  file_pattern: \"glob:**/*.csv\"\n" +
                ("plg".equals(tag) || "nip".equals(tag) ? "" : "  schema_file: " + fwd(schema) + "\n") +
                procExtra +
                topExtra;
        Path p = dir.resolve("pipe_" + tag + ".toon");
        Files.writeString(p, pipe, StandardCharsets.UTF_8);
        return PipelineConfig.load(p.toString());
    }
}
