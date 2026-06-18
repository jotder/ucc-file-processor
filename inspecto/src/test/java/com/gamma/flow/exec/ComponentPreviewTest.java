package com.gamma.flow.exec;

import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ComponentPreview} (T18): dry-run a transform over sample rows through the production
 * {@link RowShaper} on a throwaway DuckDB — the same logic a real run uses, scratch-only.
 */
class ComponentPreviewTest {

    /** Ordered rows (a real JSON body decodes to an ordered map), so the input column order is deterministic. */
    private static Map<String, Object> row(String id, String grp, String amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("grp", grp);
        m.put("amt", amt);
        return m;
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(
            row("1", "a", "150"), row("2", "b", "50"), row("3", "a", "200"));

    private static ComponentPreview.RelationPreview rel(ComponentPreview.Result r, String rel) {
        return r.relations().stream().filter(p -> p.rel().equals(rel)).findFirst().orElseThrow();
    }

    @Test
    void filterPreviewSplitsKeptAndDropped() throws Exception {
        FlowNode node = FlowNode.of("f", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100"));
        ComponentPreview.Result r = ComponentPreview.transform(node, SAMPLE);

        assertEquals(List.of("id", "grp", "amt"), r.inputColumns());
        assertEquals(2, rel(r, FlowRel.DATA).rowCount());        // ids 1, 3
        assertEquals(1, rel(r, FlowRel.DROPPED).rowCount());     // id 2
        assertEquals("1", rel(r, FlowRel.DATA).rows().get(0).get("id").toString());
    }

    @Test
    void mapPreviewProjectsColumns() throws Exception {
        FlowNode node = FlowNode.of("m", "transform.map",
                Map.of("columns", List.of(Map.of("name", "ident", "expr", "id"),
                        Map.of("name", "double_amt", "expr", "CAST(amt AS INT) * 2"))));
        ComponentPreview.Result r = ComponentPreview.transform(node, SAMPLE);

        ComponentPreview.RelationPreview data = rel(r, FlowRel.DATA);
        assertEquals(3, data.rowCount());
        Map<String, Object> row0 = data.rows().get(0);
        assertTrue(row0.containsKey("ident"));
        assertTrue(row0.containsKey("double_amt"));
    }

    @Test
    void emptySampleAndUnsupportedTypeAreRejected() {
        FlowNode filter = FlowNode.of("f", "transform.filter", Map.of("where", "true"));
        assertThrows(IllegalArgumentException.class, () -> ComponentPreview.transform(filter, List.of()));
        // merge is multi-input → not previewable via the single-input shaper
        FlowNode merge = FlowNode.of("g", "transform.merge", Map.of("type", "union"));
        assertThrows(IllegalArgumentException.class, () -> ComponentPreview.transform(merge, SAMPLE));
    }

    // ── grammar ──────────────────────────────────────────────────────────────────

    @Test
    void grammarPreviewParsesRawTextWithDialect() throws Exception {
        Map<String, Object> grammar = Map.of("delimiter", "|", "has_header", true);
        ComponentPreview.GrammarResult r =
                ComponentPreview.grammar(grammar, "a|b|c\n1|2|3\n4|5|6\n");

        assertEquals(List.of("a", "b", "c"), r.columns());
        assertEquals(2, r.rowCount());
        assertEquals(0, r.rejectedRows());
        assertEquals("1", r.rows().get(0).get("a").toString());
    }

    @Test
    void grammarPreviewRejectsEmptyText() {
        assertThrows(IllegalArgumentException.class,
                () -> ComponentPreview.grammar(Map.of("delimiter", ","), "   "));
    }

    // ── schema ───────────────────────────────────────────────────────────────────

    @Test
    void schemaPreviewSplitsDataVsRejectedByCast() throws Exception {
        Map<String, Object> schema = Map.of("fields", List.of(
                Map.of("name", "id", "type", "integer"),
                Map.of("name", "amt", "type", "double")));
        List<Map<String, Object>> sample = List.of(
                two("id", "1", "amt", "150"),     // ok
                two("id", "x", "amt", "50"),       // id not int → rejected
                two("id", "3", "amt", "abc"));     // amt not double → rejected

        ComponentPreview.Result r = ComponentPreview.schema(schema, sample);
        assertEquals(1, rel(r, "data").rowCount());
        assertEquals(2, rel(r, "rejected").rowCount());
        assertEquals("1", rel(r, "data").rows().get(0).get("id").toString());
    }

    // ── sink ─────────────────────────────────────────────────────────────────────

    @Test
    void sinkPreviewValidatesStoreFormatAndPartitions() {
        Map<String, Object> sink = Map.of("store", "out", "format", "parquet",
                "partitions", List.of("year"));
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);

        assertEquals("out", r.store());
        assertEquals(3, r.rowCount());
        // "year" is not a sample column → exactly one partition warning, nothing else
        assertEquals(1, r.warnings().size());
        assertTrue(r.warnings().get(0).contains("year"));
    }

    @Test
    void sinkPreviewWarnsOnMissingStoreAndBadFormat() {
        Map<String, Object> sink = Map.of("format", "xlsx");   // no store, unknown format
        ComponentPreview.SinkResult r = ComponentPreview.sink(sink, SAMPLE);
        assertNull(r.store());
        assertEquals(2, r.warnings().size());
    }

    private static Map<String, Object> two(String k1, String v1, String k2, String v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
