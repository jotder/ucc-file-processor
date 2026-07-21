package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PipelineDryRun} (T18): a bounded sample through a flow's transform→sink subgraph, scratch-only. */
class PipelineDryRunTest {

    private static Map<String, Object> row(String id, String amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("amt", amt);
        return m;
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(row("1", "150"), row("2", "50"), row("3", "200"));

    private static PipelineDryRun.NodeDryRun node(PipelineDryRun.Result r, String id) {
        return r.nodes().stream().filter(n -> n.node().equals(id)).findFirst().orElseThrow();
    }

    private static int relCount(PipelineDryRun.NodeDryRun n, String rel) {
        return n.relations().stream().filter(x -> x.rel().equals(rel)).findFirst().orElseThrow().rowCount();
    }

    @Test
    void runsSampleThroughTransformToSinkWithCounts() throws Exception {
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100")),
                        new PipelineNode("sink", "sink.persistent", "Big", null, Map.of("store", "big"), null)),
                List.of(PipelineEdge.data("acq", "flt"), PipelineEdge.data("flt", "sink")));

        PipelineDryRun.Result r = PipelineDryRun.run(g, SAMPLE);

        assertEquals("acq", r.seedNode());                       // no parser → seed at the entry node
        PipelineDryRun.NodeDryRun flt = node(r, "flt");
        assertEquals(2, relCount(flt, PipelineRel.DATA));            // amt 150, 200 kept
        assertEquals(1, relCount(flt, PipelineRel.DROPPED));         // amt 50 dropped

        assertEquals(1, r.sinks().size());
        PipelineDryRun.SinkDryRun sink = r.sinks().get(0);
        assertEquals("sink", sink.node());
        assertEquals("big", sink.store());
        assertEquals(2, sink.rowCount());                        // the sink receives the filter's data branch
        assertFalse(sink.rows().isEmpty());
    }

    @Test
    void emptySampleIsRejected() {
        PipelineGraph g = new PipelineGraph("demo", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("sink", "sink.persistent", "S", null, Map.of("store", "s"), null)),
                List.of(PipelineEdge.data("acq", "sink")));
        assertThrows(IllegalArgumentException.class, () -> PipelineDryRun.run(g, List.of()));
    }
}
