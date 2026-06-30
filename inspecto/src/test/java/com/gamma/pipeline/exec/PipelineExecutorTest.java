package com.gamma.pipeline.exec;

import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T12 — {@link PipelineExecutor} end-to-end over embedded DuckDB: a {@code parse → filter → route → 2 sinks}
 * flow is walked topologically, each transform runs through {@link RowShaper} (T10), relations are routed
 * along the edges to the right sink, and the {@link BranchCommitCoordinator} (T11) commits both sink
 * branches then finalises the source exactly once.
 */
class PipelineExecutorTest {

    @TempDir Path dir;
    private File db;
    private Connection conn;

    @BeforeEach
    void open() throws Exception {
        db = DuckDbUtil.tempDbFile("fx_");
        conn = DuckDbUtil.openConnection(db);
    }

    @AfterEach
    void close() throws Exception {
        if (conn != null) conn.close();
        DuckDbUtil.deleteTempDb(db);
    }

    @Test
    void walksRoutesAndCommitsEachSinkBranchThenFinalisesOnce() throws Exception {
        // parse stage already produced this relation; the executor runs the downstream subgraph
        sql("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");

        PipelineGraph g = new PipelineGraph("ROUTE_ETL", true,
                List.of(
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("f", "transform.filter", Map.of("where", "amt >= 100")),
                        PipelineNode.of("r", "transform.route", Map.of(
                                "mode", "case",
                                "branches", List.of(
                                        Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        PipelineNode.of("sink_hi", "sink.persistent", Map.of(FlowStoresStoreKey, "hi")),
                        PipelineNode.of("sink_lo", "sink.persistent", Map.of(FlowStoresStoreKey, "lo"))),
                List.of(
                        PipelineEdge.data("parse", "f"),
                        PipelineEdge.data("f", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "sink_hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "sink_lo")));

        BranchCommitCoordinator coordinator =
                new BranchCommitCoordinator(new BranchCommitLog(dir.resolve("branch_commit.csv").toString()));
        List<String> written = new ArrayList<>();
        int[] finalised = {0};

        PipelineExecutor.ExecResult res = PipelineExecutor.execute(conn, g, "parse", "parsed", "batch1",
                coordinator, (sink, table) -> written.add(sink.id()), () -> finalised[0]++);

        // both sinks are branches, fed the routed relations
        assertEquals(java.util.Set.of("sink_hi", "sink_lo"), res.sinkInputs().keySet());
        // amt>=100 filter keeps {150,200}; route hi(>=200)->id3, lo(<200)->id1
        assertEquals(List.of(3), ids(res.sinkInputs().get("sink_hi")));
        assertEquals(List.of(1), ids(res.sinkInputs().get("sink_lo")));

        // commit-split: each sink branch written once, source finalised exactly once
        assertEquals(java.util.Set.of("sink_hi", "sink_lo"), java.util.Set.copyOf(written));
        assertEquals(2, written.size());
        assertEquals(1, finalised[0]);
        assertTrue(res.commit().sourceFinalized());
    }

    @Test
    void rerunIsIdempotent_noRewriteNoDoubleFinalise() throws Exception {
        sql("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(3,200)) t(id,amt)");
        PipelineGraph g = new PipelineGraph("LIN_ETL", true,
                List.of(PipelineNode.of("parse", "parser"),
                        PipelineNode.of("sink", "sink.persistent", Map.of(FlowStoresStoreKey, "out"))),
                List.of(PipelineEdge.data("parse", "sink")));

        String logPath = dir.resolve("bc.csv").toString();
        List<String> written = new ArrayList<>();
        int[] finalised = {0};

        PipelineExecutor.execute(conn, g, "parse", "parsed", "b",
                new BranchCommitCoordinator(new BranchCommitLog(logPath)),
                (s, t) -> written.add(s.id()), () -> finalised[0]++);
        assertEquals(List.of("sink"), written);
        assertEquals(1, finalised[0]);

        // a second run over the same durable ledger does not re-write or re-finalise
        PipelineExecutor.ExecResult res2 = PipelineExecutor.execute(conn, g, "parse", "parsed", "b",
                new BranchCommitCoordinator(new BranchCommitLog(logPath)),
                (s, t) -> written.add(s.id()), () -> finalised[0]++);
        assertEquals(List.of("sink"), written, "sink not re-written on replay");
        assertEquals(1, finalised[0], "source not re-finalised on replay");
        assertFalse(res2.commit().sourceFinalized());
    }

    @Test
    void disabledSinkIsNotCommittedAsABranch() throws Exception {
        sql("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(3,200)) t(id,amt)");
        // two sinks consume the parser's data; sink_b is disabled (§3.6) so it is bypassed
        PipelineGraph g = new PipelineGraph("EN_ETL", true,
                List.of(PipelineNode.of("parse", "parser"),
                        PipelineNode.of("sink_a", "sink.persistent", Map.of(FlowStoresStoreKey, "a")),
                        PipelineNode.of("sink_b", "sink.persistent", Map.of(FlowStoresStoreKey, "b", "enabled", false))),
                List.of(PipelineEdge.data("parse", "sink_a"), PipelineEdge.data("parse", "sink_b")));

        List<String> written = new ArrayList<>();
        int[] finalised = {0};
        PipelineExecutor.ExecResult res = PipelineExecutor.execute(conn, g, "parse", "parsed", "b",
                new BranchCommitCoordinator(new BranchCommitLog(dir.resolve("bc2.csv").toString())),
                (s, t) -> written.add(s.id()), () -> finalised[0]++);

        assertEquals(java.util.Set.of("sink_a"), res.sinkInputs().keySet(), "disabled sink_b is not a branch");
        assertEquals(List.of("sink_a"), written);
        assertEquals(1, finalised[0]);
    }

    @Test
    void provenanceCollectorRecordsPerNodePerRelationshipCounts() throws Exception {
        // same route flow as above: parse(3) -> filter(amt>=100: data 2 / dropped 1) -> route(hi>=200,lo<200) -> 2 sinks
        sql("CREATE TABLE parsed AS SELECT * FROM (VALUES (1,150),(2,50),(3,200)) t(id,amt)");
        PipelineGraph g = new PipelineGraph("PROV_ETL", true,
                List.of(
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("f", "transform.filter", Map.of("where", "amt >= 100")),
                        PipelineNode.of("r", "transform.route", Map.of(
                                "mode", "case",
                                "branches", List.of(
                                        Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        PipelineNode.of("sink_hi", "sink.persistent", Map.of(FlowStoresStoreKey, "hi")),
                        PipelineNode.of("sink_lo", "sink.persistent", Map.of(FlowStoresStoreKey, "lo"))),
                List.of(
                        PipelineEdge.data("parse", "f"),
                        PipelineEdge.data("f", "r"),
                        new PipelineEdge("r", PipelineRel.route("hi"), "sink_hi"),
                        new PipelineEdge("r", PipelineRel.route("lo"), "sink_lo")));

        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        PipelineExecutor.execute(conn, g, Map.of("parse", "parsed"), "batchP",
                new BranchCommitCoordinator(new BranchCommitLog(dir.resolve("prov_bc.csv").toString())),
                (sink, table) -> {}, () -> {},
                (nodeId, rel, rowCount) -> counts.put(nodeId + "|" + rel, rowCount));

        assertEquals(3L, counts.get("parse|data"));        // recordsIn
        assertEquals(2L, counts.get("f|data"));            // amt>=100 keeps {150,200}
        assertEquals(1L, counts.get("f|dropped"));         // {50} diverted
        assertEquals(1L, counts.get("r|route:hi"));        // {200}
        assertEquals(1L, counts.get("r|route:lo"));        // {150}
        assertEquals(1L, counts.get("sink_hi|data"));      // recordsIn at the terminal sink
        assertEquals(1L, counts.get("sink_lo|data"));

        // conservation at the filter (non-amplifying): in == out + dropped
        assertEquals(counts.get("parse|data"), counts.get("f|data") + counts.get("f|dropped"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The PipelineStores produced-store config key (kept local so the test reads cleanly). */
    private static final String FlowStoresStoreKey = com.gamma.pipeline.PipelineStores.CONFIG_STORE;

    private void sql(String s) throws SQLException {
        try (Statement st = conn.createStatement()) { st.execute(s); }
    }

    private List<Integer> ids(String table) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM \"" + table + "\" ORDER BY 1")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }
}
