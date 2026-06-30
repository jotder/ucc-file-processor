package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase-1 parity gate: a {@code lift → compile} round-trip recovers every execution input the
 * engine consumes, unchanged ({@link PipelineCompiler}). {@code assertSame} proves the IR carries the
 * <em>identical</em> typed objects (lossless), so compile-back to today's primitives is faithful.
 */
class PipelineCompilerTest {

    @Test
    void singleSchemaRoundTripIsLossless(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        PipelineGraph g = PipelineLift.lift(cfg);
        PipelineCompiler.Compiled c = PipelineCompiler.compile(g);

        assertEquals(cfg.identity().pipelineName(), c.name());
        assertEquals(cfg.active(), c.active());

        // acquisition: source sub-records + dirs.poll recovered identically
        PipelineNode acq = c.acquisition().orElseThrow();
        assertSame(cfg.source().guarantee(), acq.cfg("guarantee"));
        assertSame(cfg.source().stability(), acq.cfg("stability"));
        assertSame(cfg.source().postAction(), acq.cfg("post_action"));
        assertEquals(cfg.dirs().poll(), acq.cfg("poll"));

        // parser: the whole CsvSettings record + the single schema map, by identity
        PipelineNode parse = c.parser().orElseThrow();
        assertSame(cfg.csv(), parse.cfg("csv"));
        assertSame(cfg.schemas().single(), parse.cfg("schema"));

        // sink: output + dirs recovered; exactly one sink (single schema)
        assertEquals(1, c.sinks().size());
        PipelineNode sink = c.sinks().get(0);
        assertEquals(cfg.output().format(), sink.cfg("format"));
        assertEquals(cfg.dirs().database(), sink.cfg("database"));

        // dedup: path mode + duplicate_check on ⇒ exactly the marker subsystem, no fingerprint
        assertEquals(List.of("transform.dedup.marker"), c.dedups().stream().map(PipelineNode::type).toList());
        assertTrue(c.gap().isEmpty());
    }

    @Test
    void compileGroupsNodesByRole() {
        PipelineGraph g = new PipelineGraph("X", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("dm", "transform.dedup.marker"),
                        PipelineNode.of("df", "transform.dedup.fingerprint"),
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("gap", "gap"),
                        PipelineNode.of("s1", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "a")),
                        PipelineNode.of("s2", "sink.materialized", Map.of(PipelineStores.CONFIG_STORE, "b"))),
                List.of());
        PipelineCompiler.Compiled c = PipelineCompiler.compile(g);

        assertEquals("acq", c.acquisition().orElseThrow().id());
        assertEquals("parse", c.parser().orElseThrow().id());
        assertEquals("gap", c.gap().orElseThrow().id());
        assertEquals(List.of("dm", "df"), c.dedups().stream().map(PipelineNode::id).toList());
        assertEquals(List.of("s1", "s2"), c.sinks().stream().map(PipelineNode::id).toList());
    }
}
