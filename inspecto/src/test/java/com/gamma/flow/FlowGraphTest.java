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
                        FlowNode.of("sink", "sink.persistent"),
                        FlowNode.of("quar", "sink.persistent")),
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
                () -> g.nodes().add(FlowNode.of("b", "sink.persistent")));
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
        // sink is now a family of subtypes (the bare "sink" is gone)
        assertFalse(FlowNodeTypes.isKnown("sink"));
        assertTrue(FlowNodeTypes.isKnown("sink.persistent"));
        assertTrue(FlowNodeTypes.isKnown("sink.materialized"));
        assertTrue(FlowNodeTypes.isKnown("sink.view"));
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

    @Test
    void nodeCategoriesGroupTheTaxonomy() {
        // categories drive palette grouping + role checks (not the literal type string)
        assertEquals(NodeCategory.SOURCE,    FlowNodeTypes.categoryOf("acquisition").orElseThrow());
        assertEquals(NodeCategory.SOURCE,    FlowNodeTypes.categoryOf("adapter").orElseThrow());
        assertEquals(NodeCategory.PARSE,     FlowNodeTypes.categoryOf("parser").orElseThrow());
        assertEquals(NodeCategory.TRANSFORM, FlowNodeTypes.categoryOf("transform.map").orElseThrow());
        assertEquals(NodeCategory.CONTROL,   FlowNodeTypes.categoryOf("gap").orElseThrow());

        // all three sink subtypes share the SINK category, so sink detection is family-based
        assertTrue(FlowNodeTypes.isCategory("sink.persistent", NodeCategory.SINK));
        assertTrue(FlowNodeTypes.isCategory("sink.materialized", NodeCategory.SINK));
        assertTrue(FlowNodeTypes.isCategory("sink.view", NodeCategory.SINK));
        assertFalse(FlowNodeTypes.isCategory("transform.map", NodeCategory.SINK));
        assertTrue(FlowNodeTypes.categoryOf("does.not.exist").isEmpty());

        // catalog is UI-ready: every descriptor carries a non-blank label
        assertTrue(FlowNodeTypes.catalog().stream().allMatch(t -> !t.label().isBlank()));
        assertTrue(FlowNodeTypes.catalog().size() >= BuiltinNodeType.values().length);
    }

    @Test
    void nodeCarriesUserNameAndDescription() {
        FlowNode plain = FlowNode.of("a", "acquisition");
        assertFalse(plain.hasName());
        assertNull(plain.name());

        // a sink.view named after a business object/concept the user provides
        FlowNode view = new FlowNode("v", "sink.view", "Active subscribers",
                "Subscribers seen in the last 24h", Map.of(), null);
        assertTrue(view.hasName());
        assertEquals("Active subscribers", view.name());
        assertEquals("Subscribers seen in the last 24h", view.description());
    }
}
