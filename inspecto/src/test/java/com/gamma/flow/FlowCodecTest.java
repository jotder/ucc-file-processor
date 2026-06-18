package com.gamma.flow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FlowCodec}: lossless FlowGraph ↔ map round-trip for authored {@code *_flow.toon} (T19). */
class FlowCodecTest {

    private static FlowGraph sample() {
        return new FlowGraph("orders_flow", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        new FlowNode("sink", "sink.persistent", "Orders", "the resting store",
                                Map.of(FlowStores.CONFIG_STORE, "orders", "format", "PARQUET"), null),
                        new FlowNode("p", "parser", null, null, Map.of(), "grammar/pipe")),
                List.of(FlowEdge.data("acq", "p"),
                        new FlowEdge("p", FlowRel.UNMATCHED, "sink")));
    }

    @Test
    void roundTripsEveryFieldAndEdgeRelationship() {
        FlowGraph g = sample();
        FlowGraph back = FlowCodec.fromMap(FlowCodec.toMap(g));

        assertEquals("orders_flow", back.name());
        assertTrue(back.active());
        assertEquals(3, back.nodes().size());

        FlowNode sink = back.byId().get("sink");
        assertEquals("sink.persistent", sink.type());
        assertEquals("Orders", sink.name());
        assertEquals("the resting store", sink.description());
        assertEquals("orders", sink.cfg(FlowStores.CONFIG_STORE));
        assertEquals("PARQUET", sink.cfg("format"));

        assertEquals("grammar/pipe", back.byId().get("p").use());

        assertEquals(2, back.edges().size());
        assertTrue(back.edges().stream().anyMatch(e -> "p".equals(e.from())
                && FlowRel.UNMATCHED.equals(e.rel()) && "sink".equals(e.to())));
    }

    @Test
    void requiresNameAndWellFormedNodesEdges() {
        assertThrows(IllegalArgumentException.class, () -> FlowCodec.fromMap(Map.of("active", true)));   // no name
        assertThrows(IllegalArgumentException.class,
                () -> FlowCodec.fromMap(Map.of("name", "f", "nodes", List.of(Map.of("id", "x")))));   // node missing type
        assertThrows(IllegalArgumentException.class,
                () -> FlowCodec.fromMap(Map.of("name", "f", "edges", List.of(Map.of("from", "a")))));   // edge missing to
    }

    @Test
    void absentActiveDefaultsToFalseAndEdgeRelDefaultsToData() {
        FlowGraph g = FlowCodec.fromMap(Map.of(
                "name", "f",
                "nodes", List.of(Map.of("id", "a", "type", "acquisition"), Map.of("id", "b", "type", "sink.persistent")),
                "edges", List.of(Map.of("from", "a", "to", "b"))));   // no rel
        assertFalse(g.active());
        assertTrue(g.edges().get(0).isData());   // rel defaulted to data
    }
}
