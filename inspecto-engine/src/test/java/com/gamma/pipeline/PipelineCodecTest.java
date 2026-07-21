package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PipelineCodec}: lossless PipelineGraph ↔ map round-trip for authored {@code *_flow.toon} (T19). */
class PipelineCodecTest {

    private static PipelineGraph sample() {
        return new PipelineGraph("orders_flow", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        new PipelineNode("sink", "sink.persistent", "Orders", "the resting store",
                                Map.of(PipelineStores.CONFIG_STORE, "orders", "format", "PARQUET"), null),
                        new PipelineNode("p", "parser", null, null, Map.of(), "grammar/pipe")),
                List.of(PipelineEdge.data("acq", "p"),
                        new PipelineEdge("p", PipelineRel.UNMATCHED, "sink")));
    }

    @Test
    void roundTripsEveryFieldAndEdgeRelationship() {
        PipelineGraph g = sample();
        PipelineGraph back = PipelineCodec.fromMap(PipelineCodec.toMap(g));

        assertEquals("orders_flow", back.name());
        assertTrue(back.active());
        assertEquals(3, back.nodes().size());

        PipelineNode sink = back.byId().get("sink");
        assertEquals("sink.persistent", sink.type());
        assertEquals("Orders", sink.name());
        assertEquals("the resting store", sink.description());
        assertEquals("orders", sink.cfg(PipelineStores.CONFIG_STORE));
        assertEquals("PARQUET", sink.cfg("format"));

        assertEquals("grammar/pipe", back.byId().get("p").use());

        assertEquals(2, back.edges().size());
        assertTrue(back.edges().stream().anyMatch(e -> "p".equals(e.from())
                && PipelineRel.UNMATCHED.equals(e.rel()) && "sink".equals(e.to())));
    }

    @Test
    void requiresNameAndWellFormedNodesEdges() {
        assertThrows(IllegalArgumentException.class, () -> PipelineCodec.fromMap(Map.of("active", true)));   // no name
        assertThrows(IllegalArgumentException.class,
                () -> PipelineCodec.fromMap(Map.of("name", "f", "nodes", List.of(Map.of("id", "x")))));   // node missing type
        assertThrows(IllegalArgumentException.class,
                () -> PipelineCodec.fromMap(Map.of("name", "f", "edges", List.of(Map.of("from", "a")))));   // edge missing to
    }

    @Test
    void absentActiveDefaultsToFalseAndEdgeRelDefaultsToData() {
        PipelineGraph g = PipelineCodec.fromMap(Map.of(
                "name", "f",
                "nodes", List.of(Map.of("id", "a", "type", "acquisition"), Map.of("id", "b", "type", "sink.persistent")),
                "edges", List.of(Map.of("from", "a", "to", "b"))));   // no rel
        assertFalse(g.active());
        assertTrue(g.edges().get(0).isData());   // rel defaulted to data
    }
}
