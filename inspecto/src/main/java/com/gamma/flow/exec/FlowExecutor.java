package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.flow.BuiltinNodeType;
import com.gamma.flow.FlowEdge;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowNodeTypes;
import com.gamma.flow.FlowRel;
import com.gamma.flow.FlowValidator;
import com.gamma.flow.NodeCategory;

import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>T12 — the branch-aware topological executor.</b> Drives a {@link FlowGraph} over a DuckDB connection:
 * it walks the graph in topological order, runs each {@code transform.*} node through {@link RowShaper}
 * (T10) to produce named relations, <b>routes</b> each produced relation along its outgoing edge to the
 * consuming node, and at the {@code sink} nodes drives the {@link BranchCommitCoordinator} commit-split
 * (T11) — each sink is a branch, and the source is finalised only once every branch has committed.
 *
 * <p>Scheduling is the new piece (R3): the legacy {@code MultiSourceProcessor} fan-out is per-config with
 * no intra-pipeline branch concept; here a single batch fans across the flow's branches. This first cut is
 * a deterministic sequential topological walk (correct + ordered); running independent branches on the
 * existing vthread pool/permit pattern is a follow-up optimisation.
 *
 * <p><b>Additive:</b> this does not replace the legacy single-output {@code BatchProcessor} path. It starts
 * from a <em>seed</em> relation — the {@code data} output of the parse stage, already materialised in
 * {@code conn} by the existing ingest — and executes the downstream {@code transform → sink} subgraph.
 * Routing a legacy config's whole suite through it (byte-for-byte parity) is T5b, still future.
 *
 * <p>Relations flow by a <b>pull</b> model over the topological order: when a node is visited, each of its
 * inbound edges {@code (from, rel, to)} contributes the relation {@code rel} that {@code from} produced as
 * one of this node's inputs. {@code on_commit} edges are cross-flow and excluded from the walk.
 */
@PublicApi(since = "4.3.0")
public final class FlowExecutor {

    private FlowExecutor() {}

    /** Persists one sink node's input relation (the real sink write / DuckLake register / Parquet output). */
    @FunctionalInterface
    public interface SinkWriter {
        void write(FlowNode sink, String inputTable) throws Exception;
    }

    /** What the run produced: every node's named relations + which sinks fed which table + the commit outcome. */
    public record ExecResult(Map<String, Map<String, String>> produced,
                             Map<String, String> sinkInputs,
                             BranchCommitCoordinator.Result commit) {}

    /**
     * Execute the {@code transform → sink} subgraph downstream of {@code seedNodeId}.
     *
     * @param conn           DuckDB connection holding {@code seedTable}
     * @param g              the flow graph (validated up-front via {@link FlowValidator#validateOrThrow})
     * @param seedNodeId     the node whose {@code data} relation is {@code seedTable} (typically the parser)
     * @param seedTable      the DuckDB table the parse stage already produced
     * @param batchId        the batch being committed
     * @param coordinator    the {@link BranchCommitCoordinator} that gates source-finalisation on all sinks
     * @param sinkWriter     writes a sink branch's output (called by the coordinator, once per branch)
     * @param sourceFinalize backup → markers LAST → ledger/watermark (called once, after all sinks commit)
     */
    public static ExecResult execute(Connection conn, FlowGraph g, String seedNodeId, String seedTable,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize) throws Exception {
        return execute(conn, g, Map.of(seedNodeId, seedTable), batchId, coordinator, sinkWriter, sourceFinalize);
    }

    /**
     * Execute the {@code transform → sink} subgraph downstream of one or more <b>seed</b> nodes. Each entry of
     * {@code seeds} maps a node id to the DuckDB table/view already holding that node's {@code data} relation.
     * The common case is a single parser-seeded relation (the {@code (seedNodeId, seedTable)} overload); a flow
     * job seeds <em>one view per {@code source_store}</em> (T32 Phase C, multi-source), so a {@code transform.merge}
     * can join/union several at-rest stores into one branch.
     */
    public static ExecResult execute(Connection conn, FlowGraph g, Map<String, String> seeds,
                                     String batchId, BranchCommitCoordinator coordinator,
                                     SinkWriter sinkWriter,
                                     BranchCommitCoordinator.SourceFinalize sourceFinalize) throws Exception {
        FlowValidator.validateOrThrow(g);
        Map<String, FlowNode> byId = g.byId();

        Map<String, Map<String, String>> produced = new LinkedHashMap<>();
        seeds.forEach((nodeId, table) -> produced.put(nodeId, new LinkedHashMap<>(Map.of(FlowRel.DATA, table))));
        Map<String, String> sinkInputs = new LinkedHashMap<>();

        for (String nodeId : topoOrder(g)) {
            if (seeds.containsKey(nodeId)) continue;       // pre-seeded source/parse relation
            FlowNode node = byId.get(nodeId);
            if (!node.enabled()) continue;                 // disabled node (§3.6) — produces nothing; downstream inert
            List<FlowEdge> inbound = liveInbound(g, nodeId, produced);
            if (inbound.isEmpty()) continue;               // upstream not executed here (e.g. acquisition) — skip

            if (FlowNodeTypes.isCategory(node.type(), NodeCategory.SINK)) {
                sinkInputs.put(nodeId, tableOf(inbound.get(0), produced));   // a sink consumes one relation
            } else if (BuiltinNodeType.TRANSFORM_MERGE.type().equals(node.type())) {
                List<String> inputs = new ArrayList<>();
                for (FlowEdge e : inbound) inputs.add(tableOf(e, produced));
                produced.put(nodeId, index(RowShaper.merge(conn, node, inputs, nodeId)));
            } else if (isShapeable(node.type())) {
                produced.put(nodeId, index(RowShaper.shape(conn, node, tableOf(inbound.get(0), produced), nodeId)));
            }
            // control terminals (gap/alert/event) consume but produce no data relation — nothing to route on
        }

        if (sinkInputs.isEmpty())
            throw new IllegalStateException("flow '" + g.name() + "' produced no sink branches downstream of " + seeds.keySet());

        BranchCommitCoordinator.Result commit = coordinator.commit(batchId, new LinkedHashSet<>(sinkInputs.keySet()),
                branch -> sinkWriter.write(byId.get(branch), sinkInputs.get(branch)),
                sourceFinalize);
        return new ExecResult(produced, sinkInputs, commit);
    }

    /** What a dry-run produced: every node's named relations + which table each sink would consume. No commit. */
    public record DryRunResult(Map<String, Map<String, String>> produced, Map<String, String> sinkInputs) {}

    /**
     * <b>T18 — bounded-sample dry-run.</b> The same topological walk + {@link RowShaper} as {@link #execute}, but
     * <em>without</em> the commit: it shapes each {@code transform.*} node over the seeded relation and records
     * the table each sink would consume, so a preview can report per-node / per-edge row counts. Scratch-only —
     * no {@link BranchCommitCoordinator}, no sink write, no source finalisation. {@code seedTable} is the sample
     * already materialised in {@code conn} (typically the parser's {@code data} output).
     */
    public static DryRunResult dryRun(Connection conn, FlowGraph g, String seedNodeId, String seedTable)
            throws Exception {
        FlowValidator.validateOrThrow(g);
        Map<String, FlowNode> byId = g.byId();
        Map<String, Map<String, String>> produced = new LinkedHashMap<>();
        produced.put(seedNodeId, new LinkedHashMap<>(Map.of(FlowRel.DATA, seedTable)));
        Map<String, String> sinkInputs = new LinkedHashMap<>();

        for (String nodeId : topoOrder(g)) {
            if (nodeId.equals(seedNodeId)) continue;
            FlowNode node = byId.get(nodeId);
            if (!node.enabled()) continue;
            List<FlowEdge> inbound = liveInbound(g, nodeId, produced);
            if (inbound.isEmpty()) continue;
            if (FlowNodeTypes.isCategory(node.type(), NodeCategory.SINK)) {
                sinkInputs.put(nodeId, tableOf(inbound.get(0), produced));
            } else if (BuiltinNodeType.TRANSFORM_MERGE.type().equals(node.type())) {
                List<String> inputs = new ArrayList<>();
                for (FlowEdge e : inbound) inputs.add(tableOf(e, produced));
                produced.put(nodeId, index(RowShaper.merge(conn, node, inputs, nodeId)));
            } else if (isShapeable(node.type())) {
                produced.put(nodeId, index(RowShaper.shape(conn, node, tableOf(inbound.get(0), produced), nodeId)));
            }
        }
        return new DryRunResult(produced, sinkInputs);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Inbound edges whose source has already produced the relation they carry (the live inputs for this node). */
    private static List<FlowEdge> liveInbound(FlowGraph g, String nodeId, Map<String, Map<String, String>> produced) {
        List<FlowEdge> out = new ArrayList<>();
        for (FlowEdge e : g.edgesTo(nodeId)) {
            Map<String, String> up = produced.get(e.from());
            if (up != null && up.containsKey(e.rel())) out.add(e);
        }
        return out;
    }

    private static String tableOf(FlowEdge e, Map<String, Map<String, String>> produced) {
        return produced.get(e.from()).get(e.rel());
    }

    private static Map<String, String> index(List<RowShaper.Relation> rels) {
        Map<String, String> m = new LinkedHashMap<>();
        for (RowShaper.Relation r : rels) m.put(r.rel(), r.table());
        return m;
    }

    private static boolean isShapeable(String type) {
        return type.startsWith("transform.") && !BuiltinNodeType.TRANSFORM_MERGE.type().equals(type);
    }

    /**
     * Kahn topological order over all edges except cross-flow {@code on_commit}. The graph is a DAG over
     * data edges (the validator guarantees it); forward control/route edges keep the full order acyclic.
     */
    private static List<String> topoOrder(FlowGraph g) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (FlowNode n : g.nodes()) {
            indegree.put(n.id(), 0);
            adj.put(n.id(), new ArrayList<>());
        }
        for (FlowEdge e : g.edges()) {
            if (FlowRel.ON_COMMIT.equals(e.rel())) continue;     // cross-flow trigger, not a within-graph edge
            adj.get(e.from()).add(e.to());
            indegree.merge(e.to(), 1, Integer::sum);
        }
        Deque<String> ready = new ArrayDeque<>();
        for (var e : indegree.entrySet()) if (e.getValue() == 0) ready.add(e.getKey());
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String n = ready.poll();
            order.add(n);
            for (String m : adj.get(n))
                if (indegree.merge(m, -1, Integer::sum) == 0) ready.add(m);
        }
        if (order.size() != g.nodes().size())
            throw new IllegalStateException("flow '" + g.name() + "' is not acyclic over data/route/control edges");
        return order;
    }
}
