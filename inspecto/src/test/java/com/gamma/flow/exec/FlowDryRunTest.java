package com.gamma.flow.exec;

import com.gamma.flow.FlowEdge;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FlowDryRun} (T18): a bounded sample through a flow's transform→sink subgraph, scratch-only. */
class FlowDryRunTest {

    private static Map<String, Object> row(String id, String amt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("amt", amt);
        return m;
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(row("1", "150"), row("2", "50"), row("3", "200"));

    private static FlowDryRun.NodeDryRun node(FlowDryRun.Result r, String id) {
        return r.nodes().stream().filter(n -> n.node().equals(id)).findFirst().orElseThrow();
    }

    private static int relCount(FlowDryRun.NodeDryRun n, String rel) {
        return n.relations().stream().filter(x -> x.rel().equals(rel)).findFirst().orElseThrow().rowCount();
    }

    @Test
    void runsSampleThroughTransformToSinkWithCounts() throws Exception {
        FlowGraph g = new FlowGraph("demo", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("flt", "transform.filter", Map.of("where", "CAST(amt AS INT) >= 100")),
                        new FlowNode("sink", "sink.persistent", "Big", null, Map.of("store", "big"), null)),
                List.of(FlowEdge.data("acq", "flt"), FlowEdge.data("flt", "sink")));

        FlowDryRun.Result r = FlowDryRun.run(g, SAMPLE);

        assertEquals("acq", r.seedNode());                       // no parser → seed at the entry node
        FlowDryRun.NodeDryRun flt = node(r, "flt");
        assertEquals(2, relCount(flt, FlowRel.DATA));            // amt 150, 200 kept
        assertEquals(1, relCount(flt, FlowRel.DROPPED));         // amt 50 dropped

        assertEquals(1, r.sinks().size());
        FlowDryRun.SinkDryRun sink = r.sinks().get(0);
        assertEquals("sink", sink.node());
        assertEquals("big", sink.store());
        assertEquals(2, sink.rowCount());                        // the sink receives the filter's data branch
        assertFalse(sink.rows().isEmpty());
    }

    @Test
    void emptySampleIsRejected() {
        FlowGraph g = new FlowGraph("demo", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        new FlowNode("sink", "sink.persistent", "S", null, Map.of("store", "s"), null)),
                List.of(FlowEdge.data("acq", "sink")));
        assertThrows(IllegalArgumentException.class, () -> FlowDryRun.run(g, List.of()));
    }
}
