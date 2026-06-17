package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only projection of the Flow IR into plain JSON-friendly maps for the UI (doc §6, T31). Three
 * shapes: a node-type {@link #catalog()} for the editor palette, a per-graph {@link #graph(FlowGraph)}
 * for the G6 renderer, and a compact {@link #summary(FlowGraph)} for the flows list.
 *
 * <p><b>Structural only.</b> A node carries its id / type / {@link NodeCategory category} / label +
 * the user {@code name}/{@code description} + edge relationships + store hints (a sink's produced
 * {@code store}, a consumer's {@code sourceStore}, the sink kind) — <em>not</em> the raw typed config
 * (which carries live {@code PipelineConfig} sub-records / {@code SchemaSelector} / schema maps). The
 * node inspector resolves effective config separately (T17). Keeping the projection structural avoids
 * dumping engine internals over the wire and keeps the payload small.
 *
 * <p>Pure functions over the IR — no engine, no I/O — so they unit-test without HTTP. Returned maps use
 * insertion order ({@link LinkedHashMap}) so the JSON field order is stable.
 */
@PublicApi(since = "4.3.0")
public final class FlowProjection {

    private FlowProjection() {}

    /** The node-type palette: every registered type's category / label / description / ports. */
    public static List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FlowNodeType t : FlowNodeTypes.catalog()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.type());
            m.put("category", t.category().name());
            m.put("label", t.label());
            m.put("description", t.description());
            m.put("accepts", List.copyOf(t.accepts()));
            m.put("emits", List.copyOf(t.emits()));
            m.put("emitsNamedRoutes", t.emitsNamedRoutes());
            out.add(m);
        }
        return out;
    }

    /** A flow's full topology for the G6 renderer: nodes + relationship-typed edges + store endpoints. */
    public static Map<String, Object> graph(FlowGraph g) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", g.name());
        out.put("active", g.active());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (FlowNode n : g.nodes()) nodes.add(node(n));
        out.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (FlowEdge e : g.edges()) edges.add(edge(e));
        out.put("edges", edges);
        out.put("produces", List.copyOf(FlowStores.produced(g)));
        out.put("consumes", List.copyOf(FlowStores.consumed(g)));
        return out;
    }

    /** A compact entry for the flows list: name + gate + node/edge counts + store endpoints. */
    public static Map<String, Object> summary(FlowGraph g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("active", g.active());
        m.put("nodeCount", g.nodes().size());
        m.put("edgeCount", g.edges().size());
        m.put("produces", List.copyOf(FlowStores.produced(g)));
        m.put("consumes", List.copyOf(FlowStores.consumed(g)));
        return m;
    }

    private static Map<String, Object> node(FlowNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type());
        m.put("category", FlowNodeTypes.categoryOf(n.type()).map(Enum::name).orElse(NodeCategory.TRANSFORM.name()));
        m.put("label", FlowNodeTypes.get(n.type()).map(FlowNodeType::label).orElse(n.type()));
        if (n.hasName()) m.put("name", n.name());                              // user-given (may name a business object)
        if (n.description() != null && !n.description().isBlank()) m.put("description", n.description());
        if (n.hasUse()) m.put("use", n.use());
        // store hints for the viz — string only, never the raw typed config
        Object store = n.cfg(FlowStores.CONFIG_STORE);
        if (store != null && !store.toString().isBlank()) m.put("store", store.toString());
        Object sourceStore = n.cfg(FlowStores.CONFIG_SOURCE_STORE);
        if (sourceStore != null && !sourceStore.toString().isBlank()) m.put("sourceStore", sourceStore.toString());
        // sink subtype styles the node: persistent/materialized rest on disk, view is logical (no glyph)
        if (FlowNodeTypes.isCategory(n.type(), NodeCategory.SINK)) {
            m.put("sinkKind", sinkKind(n.type()));
            m.put("restsOnDisk", !n.type().endsWith(".view"));
        }
        return m;
    }

    /** The sink subtype suffix ({@code sink.persistent} → {@code persistent}); the bare type otherwise. */
    private static String sinkKind(String type) {
        int dot = type.indexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }

    private static Map<String, Object> edge(FlowEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", e.from());
        m.put("to", e.to());
        m.put("rel", e.rel());
        m.put("kind", e.isData() ? "data" : FlowRel.isRoute(e.rel()) ? "route" : "control");
        if (FlowRel.isRoute(e.rel())) m.put("routeKey", FlowRel.routeKey(e.rel()));
        return m;
    }
}
