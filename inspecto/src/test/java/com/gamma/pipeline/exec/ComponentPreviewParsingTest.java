package com.gamma.pipeline.exec;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentPreview#parsing} (v5.1.0, stream onboarding's raw→parsed sample hop): a draft's
 * {@code parsing:} settings — interpreted by the real config parser — read a pasted sample with
 * the same DuckDB idioms the ingest engine uses, schema-less. Real DuckDB, scratch-only.
 */
class ComponentPreviewParsingTest {

    /** A minimal schema-less draft carrying just the {@code parsing:} block under test. */
    private static PipelineConfig cfg(Map<String, Object> parsing) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "PREVIEW");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        if (parsing != null) m.put("parsing", parsing);
        return PipelineConfig.fromMap(m);
    }

    @Test
    void delimitedUsesTheDraftDialect() throws Exception {
        PipelineConfig c = cfg(Map.of(
                "frontend", "delimited",
                "delimited", Map.of("delimiter", "|", "has_header", true)));
        ComponentPreview.GrammarResult r =
                ComponentPreview.parsing(c, "id|city\n1|london\n2|paris\n");
        assertEquals(List.of("id", "city"), r.columns(), "header names become columns");
        assertEquals(2, r.rowCount());
        assertEquals("london", String.valueOf(r.rows().get(0).get("city")));
    }

    @Test
    void delimitedKeepsEveryColumnVarcharLikeProductionIngest() throws Exception {
        // Raw ingest is 100% VARCHAR; auto-detect must not type a date column into a DuckDB DATE
        // (which is also not JSON-serializable on the wire — found by the P2 live walk).
        ComponentPreview.GrammarResult r = ComponentPreview.parsing(
                cfg(null), "id,when\n1,2026-07-15\n");
        assertEquals("2026-07-15", r.rows().get(0).get("when"));
        assertInstanceOf(String.class, r.rows().get(0).get("when"));
    }

    @Test
    void delimitedDefaultsToCommaAndAHeaderLine() throws Exception {
        // Engine default dialect: comma-delimited, has_header true — the first line names columns.
        ComponentPreview.GrammarResult r =
                ComponentPreview.parsing(cfg(null), "id,city\n1,london\n2,paris\n");
        assertEquals(List.of("id", "city"), r.columns(), "header line names the columns");
        assertEquals(2, r.rowCount());
    }

    @Test
    void fixedWidthProjectsSlicesUnderTheirOwnNames() throws Exception {
        // has_header defaults true (engine-wide csv setting) — a headerless fixture says so.
        PipelineConfig c = cfg(Map.of(
                "frontend", "fixedwidth",
                "delimited", Map.of("has_header", false),
                "fixedwidth", Map.of(
                        "min_record_length", 8,
                        "fields", List.of(
                                Map.of("name", "id", "start", 0, "length", 3),
                                Map.of("name", "city", "start", 3, "length", 6)))));
        // Third line is shorter than min_record_length → dropped, like the engine does.
        ComponentPreview.GrammarResult r =
                ComponentPreview.parsing(c, "001london\n002paris\nxx\n");
        assertEquals(List.of("id", "city"), r.columns());
        assertEquals(2, r.rowCount());
        assertEquals("001", r.rows().get(0).get("id"));
        assertEquals("paris", r.rows().get(1).get("city"));
    }

    @Test
    void textRegexProjectsNamedGroupsAndDropsNonMatches() throws Exception {
        PipelineConfig c = cfg(Map.of(
                "frontend", "text_regex",
                "text_regex", Map.of("pattern", "(?P<level>[A-Z]+) (?P<msg>.+)")));
        ComponentPreview.GrammarResult r =
                ComponentPreview.parsing(c, "INFO started\nignored banner line\nERROR boom\n");
        assertEquals(List.of("level", "msg"), r.columns());
        assertEquals(2, r.rowCount(), "the non-matching line is dropped");
        assertEquals("INFO", r.rows().get(0).get("level"));
        assertEquals("boom", r.rows().get(1).get("msg"));
    }

    @Test
    void ndjsonKeysBecomeColumnsAndMalformedLinesAreSkipped() throws Exception {
        PipelineConfig c = cfg(Map.of("frontend", "json"));
        ComponentPreview.GrammarResult r = ComponentPreview.parsing(c,
                "{\"a\": 1, \"b\": \"x\"}\n{oops\n{\"a\": 2, \"b\": \"y\"}\n");
        assertEquals(2, r.rowCount(), "malformed NDJSON line skipped: " + r.rows());
        assertEquals(List.of("a", "b"), r.columns(), "keys become columns, first-seen order");
        assertEquals(1, r.rejectedRows(), "the dropped invalid line is counted");
        assertEquals("y", r.rows().get(1).get("b"), "values extracted per key");
    }

    @Test
    void binaryFixedWidthIsRejected() throws Exception {
        PipelineConfig c = cfg(Map.of(
                "frontend", "fixedwidth",
                "fixedwidth", Map.of(
                        "record", "bytes", "record_length", 9,
                        "fields", List.of(Map.of("name", "id", "start", 0, "length", 3)))));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ComponentPreview.parsing(c, "001london"));
        assertTrue(ex.getMessage().contains("binary fixed-width"), ex.getMessage());
    }

    @Test
    void pluginFrontendIsRejectedClearly(@TempDir java.nio.file.Path tmp) throws Exception {
        // A plugin config must carry segments (schema files that exist) even as a draft.
        java.nio.file.Path seg = tmp.resolve("seg_schema.toon");
        java.nio.file.Files.writeString(seg, "name: seg\n");
        PipelineConfig c = cfg(Map.of(
                "frontend", "plugin",
                "plugin", Map.of(
                        "ingester", "com.example.FakeIngester",
                        "segments", Map.of("main", seg.toString().replace('\\', '/')))));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ComponentPreview.parsing(c, "raw bytes"));
        assertTrue(ex.getMessage().contains("plugin"), ex.getMessage());
    }

    @Test
    void blankSampleIsRejected() throws Exception {
        PipelineConfig c = cfg(null);
        assertThrows(IllegalArgumentException.class, () -> ComponentPreview.parsing(c, "  "));
    }
}
