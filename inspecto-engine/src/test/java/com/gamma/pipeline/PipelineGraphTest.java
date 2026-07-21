package com.gamma.pipeline;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for the Flow IR ({@link PipelineGraph}/{@link PipelineNode}/{@link PipelineEdge}),
 * the relationship vocabulary ({@link PipelineRel}) and the node-type registry
 * ({@link PipelineNodeTypes}). Pure data + lookup — no TestBed, no engine.
 */
class PipelineGraphTest {

    @Test
    void edgeBlankRelNormalisesToData() {
        assertEquals(PipelineRel.DATA, new PipelineEdge("a", "  ", "b").rel());
        assertEquals(PipelineRel.DATA, new PipelineEdge("a", null, "b").rel());
        assertTrue(PipelineEdge.data("a", "b").isData());
        assertFalse(new PipelineEdge("a", PipelineRel.UNMATCHED, "b").isData());
        assertThrows(NullPointerException.class, () -> new PipelineEdge(null, PipelineRel.DATA, "b"));
    }

    @Test
    void routeRelHelpers() {
        String r = PipelineRel.route("emea");
        assertEquals("route:emea", r);
        assertTrue(PipelineRel.isRoute(r));
        assertEquals("emea", PipelineRel.routeKey(r));
        assertFalse(PipelineRel.isRoute(PipelineRel.DATA));
        assertFalse(PipelineRel.isRoute("route:"));        // empty key is not a route
        assertNull(PipelineRel.routeKey(PipelineRel.DATA));
    }

    @Test
    void nodeConfigIsImmutableDefensiveCopy() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("k", 1);
        PipelineNode n = PipelineNode.of("p", "parser", cfg);
        cfg.put("k", 2);                                   // mutate the original
        assertEquals(1, n.cfg("k"));                       // copy is unaffected
        assertThrows(UnsupportedOperationException.class, () -> n.config().put("x", 9));
        assertFalse(n.hasUse());
        assertTrue(new PipelineNode("p", "parser", Map.of(), "grammar/pipe").hasUse());
        assertNull(n.cfg("missing"));
    }

    @Test
    void graphAdjacencyEntryAndDataEdges() {
        PipelineGraph g = new PipelineGraph("cdr", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("sink", "sink.persistent"),
                        PipelineNode.of("quar", "sink.persistent")),
                List.of(PipelineEdge.data("acq", "parse"),
                        PipelineEdge.data("parse", "sink"),
                        new PipelineEdge("parse", PipelineRel.UNMATCHED, "quar")));

        assertTrue(g.active());
        assertEquals("parser", g.node("parse").orElseThrow().type());
        assertTrue(g.node("nope").isEmpty());

        // entry = acq only: quar is reached by a control (unmatched) edge, so it is NOT a trigger
        assertEquals(List.of("acq"), g.entryNodes().stream().map(PipelineNode::id).toList());

        // adjacency: parse has 2 outgoing edges but only 1 is a data edge
        assertEquals(2, g.edgesFrom("parse").size());
        assertEquals(List.of("sink"), g.dataEdgesFrom("parse").stream().map(PipelineEdge::to).toList());
        assertEquals(List.of("acq"), g.edgesTo("parse").stream().map(PipelineEdge::from).toList());
        assertEquals(4, g.byId().size());
    }

    @Test
    void recordsAreImmutable() {
        PipelineGraph g = new PipelineGraph("x", false, List.of(PipelineNode.of("a", "acquisition")), List.of());
        assertThrows(UnsupportedOperationException.class,
                () -> g.nodes().add(PipelineNode.of("b", "sink.persistent")));
        assertThrows(UnsupportedOperationException.class,
                () -> g.edges().add(PipelineEdge.data("a", "b")));
    }

    @Test
    void builtinNodeTypesRegistered() {
        assertTrue(PipelineNodeTypes.isKnown("acquisition"));
        assertTrue(PipelineNodeTypes.isKnown("parser"));
        assertTrue(PipelineNodeTypes.isKnown("transform.map"));
        assertTrue(PipelineNodeTypes.isKnown("transform.filter"));        // G1
        assertTrue(PipelineNodeTypes.isKnown("transform.dedup.marker"));  // G2
        assertTrue(PipelineNodeTypes.isKnown("transform.dedup.fingerprint"));
        // sink is now a family of subtypes (the bare "sink" is gone)
        assertFalse(PipelineNodeTypes.isKnown("sink"));
        assertTrue(PipelineNodeTypes.isKnown("sink.persistent"));
        assertTrue(PipelineNodeTypes.isKnown("sink.materialized"));
        assertTrue(PipelineNodeTypes.isKnown("sink.view"));
        assertTrue(PipelineNodeTypes.isKnown("gap"));
        assertFalse(PipelineNodeTypes.isKnown("does.not.exist"));
    }

    @Test
    void nodeTypeDescriptors() {
        // a parser dispatcher emits named route branches; an acquisition node is an entry (accepts nothing)
        assertTrue(PipelineNodeTypes.get("parser").orElseThrow().emitsNamedRoutes());
        assertTrue(PipelineNodeTypes.get("acquisition").orElseThrow().accepts().isEmpty());
        assertTrue(PipelineNodeTypes.get("acquisition").orElseThrow().emits().contains(PipelineRel.GAP));
        assertTrue(PipelineNodeTypes.get("transform.filter").orElseThrow().emits().contains(PipelineRel.DROPPED));
        // gap is a reporting task: accepts gap, emits nothing
        assertTrue(PipelineNodeTypes.get("gap").orElseThrow().emits().isEmpty());
        assertTrue(PipelineNodeTypes.get("gap").orElseThrow().accepts().contains(PipelineRel.GAP));
    }

    @Test
    void nodeCategoriesGroupTheTaxonomy() {
        // categories drive palette grouping + role checks (not the literal type string)
        assertEquals(NodeCategory.SOURCE,    PipelineNodeTypes.categoryOf("acquisition").orElseThrow());
        assertEquals(NodeCategory.SOURCE,    PipelineNodeTypes.categoryOf("adapter").orElseThrow());
        assertEquals(NodeCategory.PARSE,     PipelineNodeTypes.categoryOf("parser").orElseThrow());
        assertEquals(NodeCategory.TRANSFORM, PipelineNodeTypes.categoryOf("transform.map").orElseThrow());
        assertEquals(NodeCategory.CONTROL,   PipelineNodeTypes.categoryOf("gap").orElseThrow());

        // all three sink subtypes share the SINK category, so sink detection is family-based
        assertTrue(PipelineNodeTypes.isCategory("sink.persistent", NodeCategory.SINK));
        assertTrue(PipelineNodeTypes.isCategory("sink.materialized", NodeCategory.SINK));
        assertTrue(PipelineNodeTypes.isCategory("sink.view", NodeCategory.SINK));
        assertFalse(PipelineNodeTypes.isCategory("transform.map", NodeCategory.SINK));
        assertTrue(PipelineNodeTypes.categoryOf("does.not.exist").isEmpty());

        // catalog is UI-ready: every descriptor carries a non-blank label
        assertTrue(PipelineNodeTypes.catalog().stream().allMatch(t -> !t.label().isBlank()));
        assertTrue(PipelineNodeTypes.catalog().size() >= BuiltinNodeType.values().length);
    }

    @Test
    void nodeCarriesUserNameAndDescription() {
        PipelineNode plain = PipelineNode.of("a", "acquisition");
        assertFalse(plain.hasName());
        assertNull(plain.name());

        // a sink.view named after a business object/concept the user provides
        PipelineNode view = new PipelineNode("v", "sink.view", "Active subscribers",
                "Subscribers seen in the last 24h", Map.of(), null);
        assertTrue(view.hasName());
        assertEquals("Active subscribers", view.name());
        assertEquals("Subscribers seen in the last 24h", view.description());
    }
}
