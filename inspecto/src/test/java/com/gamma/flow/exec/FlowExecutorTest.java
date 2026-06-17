package com.gamma.flow.exec;

import com.gamma.flow.FlowEdge;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
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
 * T12 — {@link FlowExecutor} end-to-end over embedded DuckDB: a {@code parse → filter → route → 2 sinks}
 * flow is walked topologically, each transform runs through {@link RowShaper} (T10), relations are routed
 * along the edges to the right sink, and the {@link BranchCommitCoordinator} (T11) commits both sink
 * branches then finalises the source exactly once.
 */
class FlowExecutorTest {

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

        FlowGraph g = new FlowGraph("ROUTE_ETL", true,
                List.of(
                        FlowNode.of("parse", "parser"),
                        FlowNode.of("f", "transform.filter", Map.of("where", "amt >= 100")),
                        FlowNode.of("r", "transform.route", Map.of(
                                "mode", "case",
                                "branches", List.of(
                                        Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        FlowNode.of("sink_hi", "sink.persistent", Map.of(FlowStoresStoreKey, "hi")),
                        FlowNode.of("sink_lo", "sink.persistent", Map.of(FlowStoresStoreKey, "lo"))),
                List.of(
                        FlowEdge.data("parse", "f"),
                        FlowEdge.data("f", "r"),
                        new FlowEdge("r", FlowRel.route("hi"), "sink_hi"),
                        new FlowEdge("r", FlowRel.route("lo"), "sink_lo")));

        BranchCommitCoordinator coordinator =
                new BranchCommitCoordinator(new BranchCommitLog(dir.resolve("branch_commit.csv").toString()));
        List<String> written = new ArrayList<>();
        int[] finalised = {0};

        FlowExecutor.ExecResult res = FlowExecutor.execute(conn, g, "parse", "parsed", "batch1",
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
        FlowGraph g = new FlowGraph("LIN_ETL", true,
                List.of(FlowNode.of("parse", "parser"),
                        FlowNode.of("sink", "sink.persistent", Map.of(FlowStoresStoreKey, "out"))),
                List.of(FlowEdge.data("parse", "sink")));

        String logPath = dir.resolve("bc.csv").toString();
        List<String> written = new ArrayList<>();
        int[] finalised = {0};

        FlowExecutor.execute(conn, g, "parse", "parsed", "b",
                new BranchCommitCoordinator(new BranchCommitLog(logPath)),
                (s, t) -> written.add(s.id()), () -> finalised[0]++);
        assertEquals(List.of("sink"), written);
        assertEquals(1, finalised[0]);

        // a second run over the same durable ledger does not re-write or re-finalise
        FlowExecutor.ExecResult res2 = FlowExecutor.execute(conn, g, "parse", "parsed", "b",
                new BranchCommitCoordinator(new BranchCommitLog(logPath)),
                (s, t) -> written.add(s.id()), () -> finalised[0]++);
        assertEquals(List.of("sink"), written, "sink not re-written on replay");
        assertEquals(1, finalised[0], "source not re-finalised on replay");
        assertFalse(res2.commit().sourceFinalized());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The FlowStores produced-store config key (kept local so the test reads cleanly). */
    private static final String FlowStoresStoreKey = com.gamma.flow.FlowStores.CONFIG_STORE;

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
