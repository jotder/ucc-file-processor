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
 * {@link FlowProjection}: the read-only IR→JSON projection that feeds the UI palette + G6 renderer
 * (doc §6, T31). Asserts the catalog carries category/ports, the graph projection is structural with
 * relationship-typed edges (and leaks no raw config), and a lifted legacy pipeline summarises.
 */
class FlowProjectionTest {

    @Test
    void catalogExposesEveryTypeWithCategoryAndPorts() {
        List<Map<String, Object>> cat = FlowProjection.catalog();
        assertTrue(cat.size() >= BuiltinNodeType.values().length);   // built-ins (+ any provider)

        Map<String, Object> view = byType(cat, "sink.view");
        assertEquals("SINK", view.get("category"));
        assertEquals("Sink (view)", view.get("label"));
        assertFalse(((String) view.get("description")).isBlank());
        assertInstanceOf(List.class, view.get("accepts"));

        assertEquals(Boolean.TRUE, byType(cat, "parser").get("emitsNamedRoutes"));
        assertEquals("SOURCE", byType(cat, "acquisition").get("category"));
    }

    @Test
    void graphProjectionIsStructuralWithTypedEdges() {
        FlowGraph g = new FlowGraph("DEMO", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        FlowNode.of("parse", "parser"),
                        new FlowNode("v", "sink.view", "Active subs", "last 24h",
                                Map.of(FlowStores.CONFIG_STORE, "active_subs"), null),
                        FlowNode.of("quar", "sink.persistent")),
                List.of(FlowEdge.data("acq", "parse"),
                        FlowEdge.data("parse", "v"),
                        new FlowEdge("parse", FlowRel.UNMATCHED, "quar"),
                        new FlowEdge("parse", FlowRel.route("emea"), "v")));

        Map<String, Object> proj = FlowProjection.graph(g);
        assertEquals("DEMO", proj.get("name"));
        assertEquals(Boolean.TRUE, proj.get("active"));
        assertEquals(List.of("active_subs"), proj.get("produces"));   // quarantine (no store) excluded

        Map<String, Object> view = nodeById(proj, "v");
        assertEquals("sink.view", view.get("type"));
        assertEquals("SINK", view.get("category"));
        assertEquals("Active subs", view.get("name"));        // user-given name
        assertEquals("last 24h", view.get("description"));
        assertEquals("active_subs", view.get("store"));
        assertEquals("view", view.get("sinkKind"));
        assertEquals(Boolean.FALSE, view.get("restsOnDisk")); // a view does not rest on disk
        assertFalse(view.containsKey("config"), "raw config must not leak into the projection");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) proj.get("edges");
        assertTrue(edges.stream().anyMatch(x -> "data".equals(x.get("kind"))
                && "parse".equals(x.get("from")) && "v".equals(x.get("to"))));
        assertTrue(edges.stream().anyMatch(x -> "control".equals(x.get("kind")) && "unmatched".equals(x.get("rel"))));
        Map<String, Object> route = edges.stream().filter(x -> "route".equals(x.get("kind"))).findFirst().orElseThrow();
        assertEquals("emea", route.get("routeKey"));
    }

    @Test
    void summaryOfLiftedLegacyPipeline(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        Map<String, Object> s = FlowProjection.summary(PipelineLift.lift(cfg));
        assertEquals(cfg.identity().pipelineName(), s.get("name"));
        assertTrue((int) s.get("nodeCount") >= 4);              // acq + dedup + parse + map + sink
        assertEquals(List.of("mini"), s.get("produces"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, Object> byType(List<Map<String, Object>> cat, String type) {
        return cat.stream().filter(m -> type.equals(m.get("type"))).findFirst()
                .orElseThrow(() -> new AssertionError("no catalog entry for " + type));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nodeById(Map<String, Object> proj, String id) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) proj.get("nodes");
        return nodes.stream().filter(n -> id.equals(n.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no node " + id));
    }
}
