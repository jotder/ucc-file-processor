package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.flow.BuiltinNodeType;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowStores;
import com.gamma.util.DuckDbUtil;

import java.io.File;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <b>T18 — flow dry-run (§7.2): "test the pipeline incrementally".</b> Runs a bounded sample through a flow's
 * {@code transform → sink} subgraph on a throwaway DuckDB and reports per-node produced relations and the rows
 * each sink would receive — the per-edge record counts an operator watches as records flow. It reuses the
 * <em>production</em> walk ({@link FlowExecutor#dryRun}, the same {@link RowShaper} as a real run) and commits
 * nothing; the scratch database is deleted afterwards.
 *
 * <p>The sample is the <b>post-parse</b> record set, so it is seeded at the flow's parser node (or, if the flow
 * has none, its entry node); the acquisition/parse stage upstream of the seed is not exercised here.
 */
@PublicApi(since = "4.3.0")
public final class FlowDryRun {

    private FlowDryRun() {}

    /** A produced relation at a node: the {@link com.gamma.flow.FlowRel} + how many rows reached it (+ a sample). */
    public record RelationCount(String rel, int rowCount, List<Map<String, Object>> rows) {}

    /** One non-sink node's outputs in the dry-run. */
    public record NodeDryRun(String node, String type, List<RelationCount> relations) {}

    /** A sink branch in the dry-run: the table it would consume, the row count, and a sample. */
    public record SinkDryRun(String node, String store, int rowCount, List<Map<String, Object>> rows) {}

    /** The dry-run outcome: where the sample was seeded, every transform node's outputs, and each sink branch. */
    public record Result(String seedNode, List<NodeDryRun> nodes, List<SinkDryRun> sinks) {}

    /** Rows materialised per relation in the result (the counts are exact; the rows are a bounded sample). */
    public static final int SAMPLE_ROWS = 50;

    private static final String SEED = "dryrun_seed";

    /**
     * Dry-run {@code g} over {@code sampleRows}. Throws {@link IllegalArgumentException} for an empty sample or a
     * flow with no parser/entry node to seed at; validation errors surface from {@link FlowExecutor#dryRun}.
     */
    public static Result run(FlowGraph g, List<Map<String, Object>> sampleRows) throws Exception {
        if (sampleRows == null || sampleRows.isEmpty())
            throw new IllegalArgumentException("at least one sample row is required");
        String seedNode = seedNodeOf(g);
        List<String> columns = ScratchTables.columnsOf(sampleRows);

        File db = DuckDbUtil.tempDbFile("dryrun_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            ScratchTables.seed(conn, SEED, columns, sampleRows);
            FlowExecutor.DryRunResult dr = FlowExecutor.dryRun(conn, g, seedNode, SEED);
            Map<String, FlowNode> byId = g.byId();

            List<NodeDryRun> nodes = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> e : dr.produced().entrySet()) {
                if (e.getKey().equals(seedNode)) continue;   // the seed is the input, not a produced node
                List<RelationCount> rels = new ArrayList<>();
                for (Map.Entry<String, String> r : e.getValue().entrySet()) {
                    rels.add(new RelationCount(r.getKey(),
                            ScratchTables.count(conn, r.getValue()),
                            ScratchTables.readRows(conn, r.getValue(), SAMPLE_ROWS)));
                }
                FlowNode n = byId.get(e.getKey());
                nodes.add(new NodeDryRun(e.getKey(), n == null ? null : n.type(), rels));
            }

            List<SinkDryRun> sinks = new ArrayList<>();
            for (Map.Entry<String, String> s : dr.sinkInputs().entrySet()) {
                FlowNode n = byId.get(s.getKey());
                Object store = n == null ? null : n.cfg(FlowStores.CONFIG_STORE);
                sinks.add(new SinkDryRun(s.getKey(), store == null ? null : store.toString(),
                        ScratchTables.count(conn, s.getValue()),
                        ScratchTables.readRows(conn, s.getValue(), SAMPLE_ROWS)));
            }
            return new Result(seedNode, nodes, sinks);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Seed the sample at the parser node (its {@code data} output) if present, else the first entry node. */
    private static String seedNodeOf(FlowGraph g) {
        for (FlowNode n : g.nodes()) {
            if (BuiltinNodeType.PARSER.type().equals(n.type())) return n.id();
        }
        if (!g.entryNodes().isEmpty()) return g.entryNodes().get(0).id();
        throw new IllegalArgumentException("flow '" + g.name() + "' has no parser or entry node to seed the sample at");
    }
}
