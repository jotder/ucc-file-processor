package com.gamma.flow;

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
 * engine consumes, unchanged ({@link FlowCompiler}). {@code assertSame} proves the IR carries the
 * <em>identical</em> typed objects (lossless), so compile-back to today's primitives is faithful.
 */
class FlowCompilerTest {

    @Test
    void singleSchemaRoundTripIsLossless(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        FlowGraph g = PipelineLift.lift(cfg);
        FlowCompiler.Compiled c = FlowCompiler.compile(g);

        assertEquals(cfg.identity().pipelineName(), c.name());
        assertEquals(cfg.active(), c.active());

        // acquisition: source sub-records + dirs.poll recovered identically
        FlowNode acq = c.acquisition().orElseThrow();
        assertSame(cfg.source().guarantee(), acq.cfg("guarantee"));
        assertSame(cfg.source().stability(), acq.cfg("stability"));
        assertSame(cfg.source().postAction(), acq.cfg("post_action"));
        assertEquals(cfg.dirs().poll(), acq.cfg("poll"));

        // parser: the whole CsvSettings record + the single schema map, by identity
        FlowNode parse = c.parser().orElseThrow();
        assertSame(cfg.csv(), parse.cfg("csv"));
        assertSame(cfg.schemas().single(), parse.cfg("schema"));

        // sink: output + dirs recovered; exactly one sink (single schema)
        assertEquals(1, c.sinks().size());
        FlowNode sink = c.sinks().get(0);
        assertEquals(cfg.output().format(), sink.cfg("format"));
        assertEquals(cfg.dirs().database(), sink.cfg("database"));

        // dedup: path mode + duplicate_check on ⇒ exactly the marker subsystem, no fingerprint
        assertEquals(List.of("transform.dedup.marker"), c.dedups().stream().map(FlowNode::type).toList());
        assertTrue(c.gap().isEmpty());
    }

    @Test
    void compileGroupsNodesByRole() {
        FlowGraph g = new FlowGraph("X", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("dm", "transform.dedup.marker"),
                        FlowNode.of("df", "transform.dedup.fingerprint"),
                        FlowNode.of("parse", "parser"),
                        FlowNode.of("gap", "gap"),
                        FlowNode.of("s1", "sink", Map.of(FlowStores.CONFIG_STORE, "a")),
                        FlowNode.of("s2", "sink", Map.of(FlowStores.CONFIG_STORE, "b"))),
                List.of());
        FlowCompiler.Compiled c = FlowCompiler.compile(g);

        assertEquals("acq", c.acquisition().orElseThrow().id());
        assertEquals("parse", c.parser().orElseThrow().id());
        assertEquals("gap", c.gap().orElseThrow().id());
        assertEquals(List.of("dm", "df"), c.dedups().stream().map(FlowNode::id).toList());
        assertEquals(List.of("s1", "s2"), c.sinks().stream().map(FlowNode::id).toList());
    }
}
