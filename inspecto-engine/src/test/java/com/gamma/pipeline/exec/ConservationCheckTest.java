package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T22 / §11.4 — the conservation invariant over data-plane provenance counts. A conserving node (filter,
 * validate, dedup, map/select/derive, case-route) must emit exactly the records it consumed; a discrepancy is
 * silent data loss (in &gt; out) or unexpected amplification (in &lt; out). Genuine amplifiers (split,
 * clone-route) and merge are excluded.
 */
class ConservationCheckTest {

    private static PipelineGraph filterFlow() {
        return new PipelineGraph("F", true,
                List.of(PipelineNode.of("src", "parser"),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        PipelineNode.of("out", "sink.persistent", Map.of("store", "o"))),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out")));
    }

    @Test
    void balancedFilterHasNoImbalance() {
        // src=3 -> filter splits into data 2 + dropped 1 (== 3 in) -> sink 2
        Map<String, Long> counts = Map.of("src|data", 3L, "flt|data", 2L, "flt|dropped", 1L, "out|data", 2L);
        assertTrue(ConservationCheck.imbalances(filterFlow(), counts).isEmpty());
    }

    @Test
    void recordsLostAtAConservingNodeIsFlaggedAsLoss() {
        // filter consumed 3 but only accounted for 2 (data 2, dropped 0) — one record vanished
        Map<String, Long> counts = Map.of("src|data", 3L, "flt|data", 2L, "flt|dropped", 0L, "out|data", 2L);
        List<ConservationCheck.Imbalance> im = ConservationCheck.imbalances(filterFlow(), counts);
        assertEquals(1, im.size());
        assertEquals("flt", im.get(0).node());
        assertEquals(3L, im.get(0).recordsIn());
        assertEquals(2L, im.get(0).recordsOut());
        assertEquals("LOSS", im.get(0).kind());
    }

    @Test
    void recordsMultipliedAtAConservingNodeIsFlaggedAsAmplification() {
        Map<String, Long> counts = Map.of("src|data", 3L, "flt|data", 5L, "flt|dropped", 0L, "out|data", 5L);
        List<ConservationCheck.Imbalance> im = ConservationCheck.imbalances(filterFlow(), counts);
        assertEquals(1, im.size());
        assertEquals("AMPLIFICATION", im.get(0).kind());
    }

    @Test
    void splitNodeIsNotFlaggedEvenThoughItAmplifies() {
        // a transform.split legitimately emits more rows than it consumed — it must NOT be flagged
        PipelineGraph g = new PipelineGraph("S", true,
                List.of(PipelineNode.of("src", "parser"),
                        PipelineNode.of("sp", "transform.split", Map.of("column", "tags")),
                        PipelineNode.of("out", "sink.persistent", Map.of("store", "o"))),
                List.of(PipelineEdge.data("src", "sp"), PipelineEdge.data("sp", "out")));
        Map<String, Long> counts = Map.of("src|data", 3L, "sp|data", 9L, "out|data", 9L);
        assertTrue(ConservationCheck.imbalances(g, counts).isEmpty());
    }

    @Test
    void caseRouteConservesButCloneRouteIsSkipped() {
        // case route: in 3 == route:hi 1 + route:lo 1 + dropped 1 → balanced
        PipelineGraph caseG = routeFlow("case");
        Map<String, Long> caseCounts = Map.of("src|data", 3L,
                "r|route:hi", 1L, "r|route:lo", 1L, "r|dropped", 1L);
        assertTrue(ConservationCheck.imbalances(caseG, caseCounts).isEmpty());

        // clone route amplifies by design (same row to several branches) — skipped, so no flag despite in≠out
        PipelineGraph cloneG = routeFlow("clone");
        Map<String, Long> cloneCounts = Map.of("src|data", 3L, "r|route:hi", 3L, "r|route:lo", 3L);
        assertTrue(ConservationCheck.imbalances(cloneG, cloneCounts).isEmpty());
    }

    private static PipelineGraph routeFlow(String mode) {
        return new PipelineGraph("R", true,
                List.of(PipelineNode.of("src", "parser"),
                        PipelineNode.of("r", "transform.route", Map.of("mode", mode)),
                        PipelineNode.of("hi", "sink.persistent", Map.of("store", "hi")),
                        PipelineNode.of("lo", "sink.persistent", Map.of("store", "lo"))),
                List.of(PipelineEdge.data("src", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "lo")));
    }
}
