package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lift coverage ({@link PipelineLift}): the two shipped shapes that matter most — a single-schema
 * linear pipeline and a multi-schema {@code selector} fan-out. Asserts node types, the data chain,
 * the dedup prefix (G2), the route branches + {@code unmatched} quarantine (G3), and that typed
 * config is carried verbatim (lossless lift).
 */
class PipelineLiftTest {

    @Test
    void liftsSingleSchemaToLinearChain(@TempDir Path dir) throws Exception {
        // TestConfigs builds a single-schema CSV pipeline with duplicate_check enabled (path mode).
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        PipelineGraph g = PipelineLift.lift(cfg);

        assertEquals(cfg.identity().pipelineName(), g.name());
        assertTrue(g.active());
        assertEquals(List.of("acq"), ids(g.entryNodes()));

        // node types
        assertType(g, "acq", "acquisition");
        assertType(g, "dedup_marker", "transform.dedup.marker");   // duplicate_check enabled (G2 marker subsystem)
        assertType(g, "parse", "parser");
        assertType(g, "map", "transform.map");
        assertType(g, "sink", "sink.persistent");

        // path mode (default) ⇒ no fingerprint dedup; single schema ⇒ no gap / no quarantine
        assertTrue(g.node("dedup_fingerprint").isEmpty());
        assertTrue(g.node("gap").isEmpty());
        assertTrue(g.node("quarantine").isEmpty());

        // the data chain acq → dedup_marker → parse → map → sink
        assertEquals(List.of("dedup_marker"), ids2(g.dataEdgesFrom("acq")));
        assertEquals(List.of("parse"), ids2(g.dataEdgesFrom("dedup_marker")));
        assertEquals(List.of("map"), ids2(g.dataEdgesFrom("parse")));
        assertEquals(List.of("sink"), ids2(g.dataEdgesFrom("map")));

        // lossless: acquisition carries typed source sub-records; parser carries csv + schema
        assertNotNull(g.node("acq").orElseThrow().cfg("guarantee"));
        assertNotNull(g.node("acq").orElseThrow().cfg("stability"));
        assertFalse(g.node("acq").orElseThrow().hasUse());          // local FS ⇒ no connection ref
        assertNotNull(g.node("parse").orElseThrow().cfg("csv"));
        assertNotNull(g.node("parse").orElseThrow().cfg("schema"));

        // the sink declares a data store (single-schema ⇒ the schema's canonicalName "mini")
        assertEquals("mini", g.node("sink").orElseThrow().cfg("store"));
        assertEquals(Set.of("mini"), PipelineStores.produced(g));
        // the lifted sink is named after the store it produces — the business object (§3.1)
        assertEquals("mini", g.node("sink").orElseThrow().name());
        // a legacy sink rests on disk (persistent), so the deletion fence treats it as a real store
        assertTrue(PipelineStores.producedStores(g).get(0).restsOnDisk());
    }

    @Test
    void liftsSelectorToRouteFanOut(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        String schema = PipelineConfigBatchTest.miniSchema();
        Path sa = dir.resolve("a.toon");
        Path sb = dir.resolve("b.toon");
        Files.writeString(sa, schema);
        Files.writeString(sb, schema);
        String d = dir.toString().replace("\\", "/");

        String toon = """
                name: SEL_ETL
                active: true
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  quarantine: %s/q
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*"
                  duplicate_check:
                    enabled: false
                  schemas[2]{column_count,file_pattern,schema_file,table}:
                    3, "glob:**/*a*", "%s", alpha
                    4, "", "%s", beta
                """.formatted(d, d, d, sa.toString().replace("\\", "/"), sb.toString().replace("\\", "/"));
        Path pipe = dir.resolve("sel_pipeline.toon");
        Files.writeString(pipe, toon);

        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        PipelineGraph g = PipelineLift.lift(cfg);

        assertEquals(List.of("acq"), ids(g.entryNodes()));
        // duplicate_check off ⇒ acq feeds the parser dispatcher directly
        assertEquals(List.of("parse"), ids2(g.dataEdgesFrom("acq")));
        assertNotNull(g.node("parse").orElseThrow().cfg("selector"));   // selector carried (G3)

        // one route branch per schema, each to its own map → sink, plus unmatched → quarantine
        assertTrue(hasEdge(g, "parse", PipelineRel.route("alpha"), "map_alpha"));
        assertTrue(hasEdge(g, "parse", PipelineRel.route("beta"), "map_beta"));
        assertTrue(hasEdge(g, "map_alpha", PipelineRel.DATA, "sink_alpha"));
        assertTrue(hasEdge(g, "map_beta", PipelineRel.DATA, "sink_beta"));
        assertTrue(hasEdge(g, "parse", PipelineRel.UNMATCHED, "quarantine"));

        assertType(g, "quarantine", "sink.persistent");
        assertEquals("alpha", g.node("sink_alpha").orElseThrow().cfg("table"));
        assertEquals("beta", g.node("sink_beta").orElseThrow().cfg("table"));

        // each branch declares its produced store; the quarantine sink has none
        assertEquals(Set.of("alpha", "beta"), PipelineStores.produced(g));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertType(PipelineGraph g, String id, String type) {
        assertEquals(type, g.node(id).orElseThrow(() -> new AssertionError("missing node " + id)).type());
    }

    private static boolean hasEdge(PipelineGraph g, String from, String rel, String to) {
        return g.edges().stream().anyMatch(e -> e.from().equals(from) && e.rel().equals(rel) && e.to().equals(to));
    }

    private static List<String> ids(List<PipelineNode> ns) {
        return ns.stream().map(PipelineNode::id).toList();
    }

    private static List<String> ids2(List<PipelineEdge> es) {
        return es.stream().map(PipelineEdge::to).toList();
    }
}
