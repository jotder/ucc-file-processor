package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialises a {@link FlowGraph} to / from a plain {@code Map} for authored {@code *_flow.toon} persistence
 * (doc §7.1 / §14 T19) — the inverse pair used by the flow CRUD endpoints + {@link FlowStore}. Unlike the
 * read-only {@link FlowProjection} (a UI-shaped, structural-only view), this is a <b>lossless</b> round-trip
 * of the authoring IR: every node's {@code id}/{@code type}/{@code name}/{@code description}/{@code use} and
 * its full local {@code config}, plus every relationship-typed edge.
 *
 * <p>Authored-flow config is plain nested maps (not the live typed {@code PipelineConfig} records the legacy
 * lift carries), so the round-trip is a straightforward map copy. Pure functions — no engine, no I/O.
 */
@PublicApi(since = "4.3.0")
public final class FlowCodec {

    private FlowCodec() {}

    /** A {@link FlowGraph} as a {@code .toon}-friendly map: {@code {name, active, nodes[], edges[]}}. */
    public static Map<String, Object> toMap(FlowGraph g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", g.name());
        m.put("active", g.active());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (FlowNode n : g.nodes()) nodes.add(nodeToMap(n));
        m.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (FlowEdge e : g.edges()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("from", e.from());
            em.put("rel", e.rel());
            em.put("to", e.to());
            edges.add(em);
        }
        m.put("edges", edges);
        return m;
    }

    private static Map<String, Object> nodeToMap(FlowNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("type", n.type());
        if (n.hasName()) m.put("name", n.name());
        if (n.description() != null && !n.description().isBlank()) m.put("description", n.description());
        if (n.hasUse()) m.put("use", n.use());
        if (!n.config().isEmpty()) m.put("config", n.config());
        return m;
    }

    /**
     * Parse a decoded {@code *_flow.toon} map into a {@link FlowGraph}. {@code name} is required; {@code active}
     * defaults to {@code false} (opt-in, matching pipelines); {@code nodes}/{@code edges} default to empty. Each
     * node requires {@code id}+{@code type}; each edge requires {@code from}+{@code to} ({@code rel} defaults to
     * {@code data}). Throws {@link IllegalArgumentException} on a malformed shape.
     */
    @SuppressWarnings("unchecked")
    public static FlowGraph fromMap(Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("flow definition is required");
        String name = str(raw.get("name"));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("flow 'name' is required");
        boolean active = toBool(raw.get("active"));

        List<FlowNode> nodes = new ArrayList<>();
        for (Object o : asList(raw.get("nodes"))) {
            if (!(o instanceof Map<?, ?> nm)) throw new IllegalArgumentException("each node must be a map");
            nodes.add(nodeFromMap((Map<String, Object>) nm));
        }
        List<FlowEdge> edges = new ArrayList<>();
        for (Object o : asList(raw.get("edges"))) {
            if (!(o instanceof Map<?, ?> em)) throw new IllegalArgumentException("each edge must be a map");
            Map<String, Object> e = (Map<String, Object>) em;
            String from = str(e.get("from"));
            String to = str(e.get("to"));
            if (from == null || to == null)
                throw new IllegalArgumentException("each edge requires 'from' and 'to'");
            edges.add(new FlowEdge(from, str(e.get("rel")), to));
        }
        return new FlowGraph(name, active, nodes, edges);
    }

    @SuppressWarnings("unchecked")
    public static FlowNode nodeFromMap(Map<String, Object> nm) {
        String id = str(nm.get("id"));
        String type = str(nm.get("type"));
        if (id == null || id.isBlank() || type == null || type.isBlank())
            throw new IllegalArgumentException("each node requires 'id' and 'type'");
        Map<String, Object> config = nm.get("config") instanceof Map<?, ?> cm
                ? (Map<String, Object>) cm : Map.of();
        return new FlowNode(id, type, str(nm.get("name")), str(nm.get("description")), config, str(nm.get("use")));
    }

    @SuppressWarnings("unchecked")
    public static FlowEdge edgeFromMap(Map<String, Object> em) {
        String from = str(em.get("from"));
        String to = str(em.get("to"));
        if (from == null || to == null) throw new IllegalArgumentException("an edge requires 'from' and 'to'");
        return new FlowEdge(from, str(em.get("rel")), to);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private static List<?> asList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return l;
        throw new IllegalArgumentException("expected a list, got " + v.getClass().getSimpleName());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(v.toString().trim());
    }
}
