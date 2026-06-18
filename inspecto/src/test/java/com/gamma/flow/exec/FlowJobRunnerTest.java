package com.gamma.flow.exec;

import com.gamma.flow.FlowEdge;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
import com.gamma.flow.FlowStore;
import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.job.JobType;
import com.gamma.service.BatchEventBus;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T32 Phase A — {@link FlowJobRunner} runs an authored flow for real over embedded DuckDB: it seeds a
 * {@code source_store} from a small on-disk Parquet dataset, executes the {@code transform → sink}
 * subgraph via the production {@link FlowExecutor}, and writes each sink {@code store}. Covers the single
 * filter→sink path, idempotent re-run (same batch id skips the committed branch), a multi-branch route to
 * two stores, and a multi-{@code source_store} union (T32 Phase C).
 */
class FlowJobRunnerTest {

    @TempDir Path tmp;

    @Test
    void runsFlowFromSourceStoreAndWritesSink() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("evt_rollup", new FlowGraph("evt_rollup", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "events")),
                        FlowNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new FlowNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(FlowEdge.data("src", "flt"), FlowEdge.data("flt", "out"))));

        JobConfig cfg = new JobConfig("nightly", JobType.FLOW, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir));
        JobResult res = new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 3), readIds(dataDir, "rollup"), "amt>=100 keeps id1(150) + id3(200), drops id2(50)");
    }

    @Test
    void rerunWithSameBatchIdSkipsTheCommittedBranch() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(3,200)");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("evt_rollup", new FlowGraph("evt_rollup", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new FlowNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(FlowEdge.data("src", "out"))));

        JobConfig cfg = new JobConfig("nightly", JobType.FLOW, null, null, true, false,
                Map.of("flow", "evt_rollup", "data_dir", dataDir, "batch_id", "fixed-1"));
        BatchEventBus bus = new BatchEventBus();

        JobResult first = new FlowJobRunner(cfg, bus, store, dataDir, auditDir).run();
        assertTrue(first.message().contains("1 file"), first.message());

        // same batch id ⇒ the branch is already durable in the branch-commit log ⇒ nothing re-written
        JobResult second = new FlowJobRunner(cfg, bus, store, dataDir, auditDir).run();
        assertTrue(second.success(), second.message());
        assertTrue(second.message().startsWith("0 file(s)"), "replay should write nothing: " + second.message());
        assertEquals(List.of(1, 3), readIds(dataDir, "rollup"), "output unchanged by the idempotent replay");
    }

    @Test
    void routesToTwoSinkStores() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events", "(1,150),(2,50),(3,200)");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("split_flow", new FlowGraph("split_flow", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "events")),
                        FlowNode.of("r", "transform.route", Map.of("mode", "case", "branches",
                                List.of(Map.of("key", "hi", "where", "amt >= 200"),
                                        Map.of("key", "lo", "where", "amt < 200")))),
                        new FlowNode("sink_hi", "sink.persistent", "Hi", null, Map.of("store", "hi"), null),
                        new FlowNode("sink_lo", "sink.persistent", "Lo", null, Map.of("store", "lo"), null)),
                List.of(FlowEdge.data("src", "r"),
                        new FlowEdge("r", FlowRel.route("hi"), "sink_hi"),
                        new FlowEdge("r", FlowRel.route("lo"), "sink_lo"))));

        JobConfig cfg = new JobConfig("splitjob", JobType.FLOW, null, null, true, false,
                Map.of("flow", "split_flow", "data_dir", dataDir));
        JobResult res = new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(3), readIds(dataDir, "hi"), "amt>=200 → id3");
        assertEquals(List.of(1, 2), readIds(dataDir, "lo"), "amt<200 → id1(150), id2(50)");
    }

    @Test
    void rejectsFlowWithNoSourceStore() throws Exception {
        String dataDir = tmp.resolve("data").toString();
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("no_src", new FlowGraph("no_src", true,
                List.of(FlowNode.of("acq", "acquisition"),
                        new FlowNode("out", "sink.persistent", "O", null, Map.of("store", "o"), null)),
                List.of(FlowEdge.data("acq", "out"))));

        JobConfig cfg = new JobConfig("j", JobType.FLOW, null, null, true, false,
                Map.of("flow", "no_src", "data_dir", dataDir));
        FlowJobRunner runner = new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, tmp.resolve("audit").toString());
        assertThrows(IllegalArgumentException.class, runner::run);
    }

    @Test
    void unionsTwoSourceStores() throws Exception {
        // T32 Phase C — a flow job seeds each source_store as its own view; a transform.merge unions them.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events_a", "(1,150),(3,200)");
        seedParquet(dataDir, "events_b", "(5,500),(2,50)");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("merged_flow", new FlowGraph("merged_flow", true,
                List.of(FlowNode.of("src_a", "acquisition", Map.of("source_store", "events_a")),
                        FlowNode.of("src_b", "acquisition", Map.of("source_store", "events_b")),
                        FlowNode.of("m", "transform.merge", Map.of("type", "union")),
                        new FlowNode("out", "sink.persistent", "Combined", null, Map.of("store", "combined"), null)),
                List.of(FlowEdge.data("src_a", "m"), FlowEdge.data("src_b", "m"), FlowEdge.data("m", "out"))));

        JobConfig cfg = new JobConfig("merge_job", JobType.FLOW, null, null, true, false,
                Map.of("flow", "merged_flow", "data_dir", dataDir));
        JobResult res = new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertEquals(List.of(1, 2, 3, 5), readIds(dataDir, "combined"),
                "union of both source_stores — all 4 rows across the two stores");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Write {@code (id,amt)} VALUES as a Parquet file under {@code <dataDir>/<store>/} (the at-rest store). */
    private static void seedParquet(String dataDir, String store, String valuesSql) throws Exception {
        Path dir = Path.of(dataDir, store);
        Files.createDirectories(dir);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,amt)) TO '"
                    + dir.resolve("seed.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Read the {@code id} column of every Parquet file under {@code <dataDir>/<store>}, ascending. */
    private static List<Integer> readIds(String dataDir, String store) throws Exception {
        String glob = dataDir.replace("\\", "/") + "/" + store + "/**/*.parquet";
        File db = DuckDbUtil.tempDbFile("rd_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM " + SqlViews.reader("PARQUET", glob, true) + " ORDER BY 1")) {
            List<Integer> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getInt(1));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
