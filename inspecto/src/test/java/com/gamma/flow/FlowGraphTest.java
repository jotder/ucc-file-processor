package com.gamma.flow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the Flow IR ({@link FlowGraph}/{@link FlowNode}/{@link FlowEdge}),
 * the relationship vocabulary ({@link FlowRel}) and the node-type registry
 * ({@link FlowNodeTypes}). Pure data + lookup — no TestBed, no engine.
 */
class FlowGraphTest {

    @Test
    void edgeBlankRelNormalisesToData() {
        assertEquals(FlowRel.DATA, new FlowEdge("a", "  ", "b").rel());
        assertEquals(FlowRel.DATA, new FlowEdge("a", null, "b").rel());
        assertTrue(FlowEdge.data("a", "b").isData());
        assertFalse(new FlowEdge("a", FlowRel.UNMATCHED, "b").isData());
        assertThrows(NullPointerException.class, () -> new FlowEdge(null, FlowRel.DATA, "b"));
    }

    @Test
    void routeRelHelpers() {
        String r = FlowRel.route("emea");
        assertEquals("route:emea", r);
        assertTrue(FlowRel.isRoute(r));
        assertEquals("emea", FlowRel.routeKey(r));
        assertFalse(FlowRel.isRoute(FlowRel.DATA));
        assertFalse(FlowRel.isRoute("route:"));        // empty key is not a route
        assertNull(FlowRel.routeKey(FlowRel.DATA));
    }

    @Test
    void nodeConfigIsImmutableDefensiveCopy() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("k", 1);
        FlowNode n = FlowNode.of("p", "parser", cfg);
        cfg.put("k", 2);                                   // mutate the original
        assertEquals(1, n.cfg("k"));                       // copy is unaffected
        assertThrows(UnsupportedOperationException.class, () -> n.config().put("x", 9));
        assertFalse(n.hasUse());
        assertTrue(new FlowNode("p", "parser", Map.of(), "grammar/pipe").hasUse());
        assertNull(n.cfg("missing"));
    }

    @Test
    void graphAdjacencyEntryAndDataEdges() {
        FlowGraph g = new FlowGraph("cdr", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("parse", "parser"),
                        FlowNode.of("sink", "sink"),
                        FlowNode.of("quar", "sink")),
                List.of(FlowEdge.data("acq", "parse"),
                        FlowEdge.data("parse", "sink"),
                        new FlowEdge("parse", FlowRel.UNMATCHED, "quar")));

        assertTrue(g.active());
        assertEquals("parser", g.node("parse").orElseThrow().type());
        assertTrue(g.node("nope").isEmpty());

        // entry = acq only: quar is reached by a control (unmatched) edge, so it is NOT a trigger
        assertEquals(List.of("acq"), g.entryNodes().stream().map(FlowNode::id).toList());

        // adjacency: parse has 2 outgoing edges but only 1 is a data edge
        assertEquals(2, g.edgesFrom("parse").size());
        assertEquals(List.of("sink"), g.dataEdgesFrom("parse").stream().map(FlowEdge::to).toList());
        assertEquals(List.of("acq"), g.edgesTo("parse").stream().map(FlowEdge::from).toList());
        assertEquals(4, g.byId().size());
    }

    @Test
    void recordsAreImmutable() {
        FlowGraph g = new FlowGraph("x", false, List.of(FlowNode.of("a", "acquisition")), List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> g.nodes().add(FlowNode.of("b", "sink")));
        assertThrows(UnsupportedOperationException.class,
                () -> g.edges().add(FlowEdge.data("a", "b")));
    }

    @Test
    void builtinNodeTypesRegistered() {
        assertTrue(FlowNodeTypes.isKnown("acquisition"));
        assertTrue(FlowNodeTypes.isKnown("parser"));
        assertTrue(FlowNodeTypes.isKnown("transform.map"));
        assertTrue(FlowNodeTypes.isKnown("transform.filter"));        // G1
        assertTrue(FlowNodeTypes.isKnown("transform.dedup.marker"));  // G2
        assertTrue(FlowNodeTypes.isKnown("transform.dedup.fingerprint"));
        assertTrue(FlowNodeTypes.isKnown("sink"));
        assertTrue(FlowNodeTypes.isKnown("gap"));
        assertFalse(FlowNodeTypes.isKnown("does.not.exist"));
    }

    @Test
    void nodeTypeDescriptors() {
        // a parser dispatcher emits named route branches; an acquisition node is an entry (accepts nothing)
        assertTrue(FlowNodeTypes.get("parser").orElseThrow().emitsNamedRoutes());
        assertTrue(FlowNodeTypes.get("acquisition").orElseThrow().accepts().isEmpty());
        assertTrue(FlowNodeTypes.get("acquisition").orElseThrow().emits().contains(FlowRel.GAP));
        assertTrue(FlowNodeTypes.get("transform.filter").orElseThrow().emits().contains(FlowRel.DROPPED));
        // gap is a reporting task: accepts gap, emits nothing
        assertTrue(FlowNodeTypes.get("gap").orElseThrow().emits().isEmpty());
        assertTrue(FlowNodeTypes.get("gap").orElseThrow().accepts().contains(FlowRel.GAP));
    }
}
