package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * <b>T24 — the combined pipeline+job topology.</b> Projects several flows into <em>one</em> graph where
     * a pipeline and the job(s)/enrichment(s) over its output meet at the <b>shared store</b> (§3.8): each
     * flow's nodes are emitted with their ids namespaced by flow ({@code <flow>/<node>}, so two flows that
     * both have an {@code acq} node don't collide), plus a synthetic <b>store node</b>
     * ({@code store:<name>}, category {@code STORE}) for every store any flow produces or consumes, wired
     * {@code producer-sink → store → consumer} — drawing the cross-flow {@code on_commit} producer→consumer
     * relationship through the table itself. The {@code links} list is the derived
     * {@link FlowStores#superimpose(Collection) superimposition} (producer, store, consumer) for reference.
     *
     * <p>The join is derived from config alone (a sink's {@code store} ↔ a consumer's {@code source_store}),
     * so it needs no {@code on_pipeline} coupling; a pipeline with no consumer simply shows its
     * {@code sink → store} leaf.
     */
    public static Map<String, Object> combined(Collection<FlowGraph> graphs) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> flows = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> stores = new LinkedHashSet<>();   // every store produced or consumed → one synthetic node

        for (FlowGraph g : graphs) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", g.name());
            f.put("active", g.active());
            flows.add(f);

            for (FlowNode n : g.nodes()) {
                Map<String, Object> nm = node(n);
                nm.put("id", qualify(g.name(), n.id()));   // namespace to avoid cross-flow id collisions
                nm.put("flow", g.name());
                nodes.add(nm);
            }
            for (FlowEdge e : g.edges()) {
                Map<String, Object> em = edge(e);
                em.put("from", qualify(g.name(), e.from()));
                // on_commit's `to` names another flow, not a local node — keep it bare so it can resolve cross-flow
                em.put("to", g.byId().containsKey(e.to()) ? qualify(g.name(), e.to()) : e.to());
                em.put("flow", g.name());
                edges.add(em);
            }
            // producer edges: each producing sink → its store node
            for (FlowStores.Produced p : FlowStores.producedStores(g)) {
                stores.add(p.store());
                edges.add(storeEdge(qualify(g.name(), p.node()), storeId(p.store()), "produces", p.restsOnDisk()));
            }
            // consumer edges: store node → each node that reads it at rest
            for (FlowNode n : g.nodes()) {
                Object src = n.cfg(FlowStores.CONFIG_SOURCE_STORE);
                if (src != null && !src.toString().isBlank()) {
                    stores.add(src.toString());
                    edges.add(storeEdge(storeId(src.toString()), qualify(g.name(), n.id()), "consumes", true));
                }
            }
        }
        for (String s : stores) {
            Map<String, Object> sn = new LinkedHashMap<>();
            sn.put("id", storeId(s));
            sn.put("type", "store");
            sn.put("category", "STORE");
            sn.put("label", s);
            sn.put("store", s);
            nodes.add(sn);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (FlowStores.Link l : FlowStores.superimpose(graphs)) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("producer", l.producer());
            lm.put("store", l.store());
            lm.put("consumer", l.consumer());
            links.add(lm);
        }
        out.put("flows", flows);
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("links", links);
        return out;
    }

    private static String qualify(String flow, String nodeId) {
        return flow + "/" + nodeId;
    }

    private static String storeId(String store) {
        return "store:" + store;
    }

    private static Map<String, Object> storeEdge(String from, String to, String rel, boolean restsOnDisk) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        m.put("rel", rel);
        m.put("kind", "store");
        m.put("restsOnDisk", restsOnDisk);
        return m;
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
