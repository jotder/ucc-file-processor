package com.gamma.flow;

import com.gamma.api.PublicApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The authored (or lifted) pipeline topology: typed {@link FlowNode}s wired by {@link FlowEdge}s.
 * This is the single IR the executor, the validator and the visualiser consume. A legacy
 * {@code *_pipeline.toon} is auto-lifted into a {@code FlowGraph}; an authored {@code *_flow.toon}
 * parses into one directly. See {@code docs/flow-graph-design.md} (§5 lift, §15 capability inventory).
 *
 * <p>Instances are immutable — {@code nodes}/{@code edges} are defensive copies. Lookup and adjacency
 * are computed on demand; graphs are small (tens of nodes), so no index is cached.
 *
 * @param name   graph name (the pipeline's canonical id)
 * @param active whether the loop scheduler should run this graph (the poll gate; default {@code false})
 * @param nodes  the typed nodes (immutable copy)
 * @param edges  the directed, relationship-carrying edges (immutable copy)
 */
@PublicApi(since = "4.3.0")
public record FlowGraph(String name, boolean active, List<FlowNode> nodes, List<FlowEdge> edges) {

    public FlowGraph {
        Objects.requireNonNull(name, "graph.name");
        nodes = (nodes == null) ? List.of() : List.copyOf(nodes);
        edges = (edges == null) ? List.of() : List.copyOf(edges);
    }

    /** The node with this id, if present. */
    public Optional<FlowNode> node(String id) {
        for (FlowNode n : nodes) if (n.id().equals(id)) return Optional.of(n);
        return Optional.empty();
    }

    /** Outgoing edges from {@code nodeId} (any relationship), in declaration order. */
    public List<FlowEdge> edgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.from().equals(nodeId)).toList();
    }

    /** Incoming edges to {@code nodeId} (any relationship), in declaration order. */
    public List<FlowEdge> edgesTo(String nodeId) {
        return edges.stream().filter(e -> e.to().equals(nodeId)).toList();
    }

    /** Outgoing {@code data} edges from {@code nodeId} — the edges the topological walk follows. */
    public List<FlowEdge> dataEdgesFrom(String nodeId) {
        return edges.stream().filter(e -> e.from().equals(nodeId) && e.isData()).toList();
    }

    /**
     * Entry (trigger) nodes: nodes with <em>no inbound edge of any relationship</em> — typically the
     * {@code acquisition} node a poll cycle / trigger starts from. Note this is stricter than "no
     * inbound {@code data} edge": a node reached only by a control edge (e.g. a quarantine {@code sink}
     * via {@code unmatched}/{@code failure}) is <b>not</b> a trigger and is excluded. Returns nodes in
     * declaration order.
     */
    public List<FlowNode> entryNodes() {
        Set<String> hasInbound = new HashSet<>();
        for (FlowEdge e : edges) hasInbound.add(e.to());
        List<FlowNode> out = new ArrayList<>();
        for (FlowNode n : nodes) if (!hasInbound.contains(n.id())) out.add(n);
        return out;
    }

    /** Nodes keyed by id (insertion order preserved). */
    public Map<String, FlowNode> byId() {
        Map<String, FlowNode> m = new LinkedHashMap<>();
        for (FlowNode n : nodes) m.put(n.id(), n);
        return m;
    }
}
