package com.gamma.flow.exec;

import com.gamma.flow.FlowEdge;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
import com.gamma.flow.FlowStore;
import com.gamma.flow.ViewDefinition;
import com.gamma.flow.ViewStore;
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

    @Test
    void registersASinkViewDefinitionWithoutWritingBytes() throws Exception {
        // T32 Phase C — a sink.view persists no bytes; the flow job records a durable view definition instead.
        Path wr = tmp.resolve("wr");
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "subs", "(1,150),(2,50),(3,200)");
        FlowStore store = new FlowStore(wr.resolve("flows"));
        store.write("subs_kpi", new FlowGraph("subs_kpi", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "subs")),
                        FlowNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new FlowNode("v", "sink.view", "ActiveSubs", null, Map.of("store", "active_subs"), null)),
                List.of(FlowEdge.data("src", "flt"), FlowEdge.data("flt", "v"))));

        JobConfig cfg = new JobConfig("kpi_job", JobType.FLOW, null, null, true, false,
                Map.of("flow", "subs_kpi", "data_dir", dataDir));
        JobResult res = new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run();

        assertTrue(res.success(), res.message());
        assertFalse(Files.exists(Path.of(dataDir, "active_subs")), "a sink.view writes no data bytes");
        ViewDefinition def = new ViewStore(wr.resolve("views")).get("active_subs").orElseThrow();
        assertEquals("subs_kpi", def.flow(), "view definition records the producing flow");
        assertEquals(List.of("subs"), def.sourceStores(), "view definition records source-store lineage");
    }

    @Test
    void incrementalReadsOnlyNewRowsPastTheWatermark() throws Exception {
        // T32 Phase C — incremental_column: run 1 reads everything, run 2 reads only rows past the watermark.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquetFile(dataDir, "events", "batch1", "(1,150),(2,50)");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("inc_flow", new FlowGraph("inc_flow", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new FlowNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(FlowEdge.data("src", "out"))));

        // run 1 — no prior watermark ⇒ reads all of batch1 (ids 1,2)
        JobConfig r1 = new JobConfig("incjob", JobType.FLOW, null, null, true, false,
                Map.of("flow", "inc_flow", "data_dir", dataDir, "incremental_column", "id", "batch_id", "inc1"));
        assertTrue(new FlowJobRunner(r1, new BatchEventBus(), store, dataDir, auditDir).run().success());
        assertEquals(List.of(1, 2), readIds(dataDir, "rollup"), "first run reads the whole store");

        // new data arrives, then run 2 — watermark=2 ⇒ reads only ids 3,4 and appends (output accumulates)
        seedParquetFile(dataDir, "events", "batch2", "(3,200),(4,300)");
        JobConfig r2 = new JobConfig("incjob", JobType.FLOW, null, null, true, false,
                Map.of("flow", "inc_flow", "data_dir", dataDir, "incremental_column", "id", "batch_id", "inc2"));
        assertTrue(new FlowJobRunner(r2, new BatchEventBus(), store, dataDir, auditDir).run().success());
        assertEquals(List.of(1, 2, 3, 4), readIds(dataDir, "rollup"), "second run appends only the new rows");
    }

    @Test
    void incrementalMultiSourceAdvancesPerSourceWatermarks() throws Exception {
        // T32 follow-up — each source_store keeps its OWN watermark, so a multi-source incremental flow
        // re-reads only the rows newer than each source's last run.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquetFile(dataDir, "events_a", "a1", "(1,10),(2,20)");
        seedParquetFile(dataDir, "events_b", "b1", "(3,15)");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("inc_merge", new FlowGraph("inc_merge", true,
                List.of(FlowNode.of("src_a", "acquisition", Map.of("source_store", "events_a")),
                        FlowNode.of("src_b", "acquisition", Map.of("source_store", "events_b")),
                        FlowNode.of("m", "transform.merge", Map.of("type", "union")),
                        new FlowNode("out", "sink.persistent", "Combined", null, Map.of("store", "combined"), null)),
                List.of(FlowEdge.data("src_a", "m"), FlowEdge.data("src_b", "m"), FlowEdge.data("m", "out"))));

        JobConfig run1 = new JobConfig("inc_job", JobType.FLOW, null, null, true, false,
                Map.of("flow", "inc_merge", "data_dir", dataDir, "incremental_column", "amt", "batch_id", "b1"));
        new FlowJobRunner(run1, new BatchEventBus(), store, dataDir, auditDir).run();
        assertEquals(List.of(1, 2, 3), readIds(dataDir, "combined"), "run 1 (no watermark) reads every source in full");

        // new rows arrive in BOTH sources, each past that source's OWN amt watermark (events_a:20, events_b:15)
        seedParquetFile(dataDir, "events_a", "a2", "(4,30)");
        seedParquetFile(dataDir, "events_b", "b2", "(5,25)");
        JobConfig run2 = new JobConfig("inc_job", JobType.FLOW, null, null, true, false,
                Map.of("flow", "inc_merge", "data_dir", dataDir, "incremental_column", "amt", "batch_id", "b2"));
        new FlowJobRunner(run2, new BatchEventBus(), store, dataDir, auditDir).run();

        // run 2 appended only id4 (events_a amt 30>20) and id5 (events_b amt 25>15) — no re-read of 1,2,3
        assertEquals(List.of(1, 2, 3, 4, 5), readIds(dataDir, "combined"),
                "per-source incremental: run 2 added only rows newer than each source's watermark");
    }

    @Test
    void sinkViewCapturesDerivedSqlForALinearPath() throws Exception {
        // T32 follow-up — a single-source, linear filter→sink.view path folds into one SELECT (derived_sql).
        Path wr = tmp.resolve("wr");
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "subs", "(1,150),(2,50),(3,200)");
        FlowStore store = new FlowStore(wr.resolve("flows"));
        store.write("subs_kpi", new FlowGraph("subs_kpi", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "subs")),
                        FlowNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new FlowNode("v", "sink.view", "ActiveSubs", null, Map.of("store", "active_subs"), null)),
                List.of(FlowEdge.data("src", "flt"), FlowEdge.data("flt", "v"))));
        JobConfig cfg = new JobConfig("kpi_job", JobType.FLOW, null, null, true, false,
                Map.of("flow", "subs_kpi", "data_dir", dataDir));
        assertTrue(new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run().success());

        ViewDefinition def = new ViewStore(wr.resolve("views")).get("active_subs").orElseThrow();
        assertNotNull(def.derivedSql(), "a single-source linear filter path yields a derived_sql");
        assertEquals(List.of(1, 3), runIds(def.derivedSql()), "derived_sql selects amt>=100 (id1, id3)");
    }

    @Test
    void sinkViewDerivedSqlIsNullForAMergedPath() throws Exception {
        // T32 follow-up — a merge (2 sources) feeding the view is NOT a single SELECT over one source → null.
        Path wr = tmp.resolve("wr");
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedParquet(dataDir, "events_a", "(1,150)");
        seedParquet(dataDir, "events_b", "(2,200)");
        FlowStore store = new FlowStore(wr.resolve("flows"));
        store.write("merge_view", new FlowGraph("merge_view", true,
                List.of(FlowNode.of("src_a", "acquisition", Map.of("source_store", "events_a")),
                        FlowNode.of("src_b", "acquisition", Map.of("source_store", "events_b")),
                        FlowNode.of("m", "transform.merge", Map.of("type", "union")),
                        new FlowNode("v", "sink.view", "Merged", null, Map.of("store", "merged"), null)),
                List.of(FlowEdge.data("src_a", "m"), FlowEdge.data("src_b", "m"), FlowEdge.data("m", "v"))));
        JobConfig cfg = new JobConfig("mv_job", JobType.FLOW, null, null, true, false,
                Map.of("flow", "merge_view", "data_dir", dataDir));
        assertTrue(new FlowJobRunner(cfg, new BatchEventBus(), store, dataDir, auditDir).run().success());

        ViewDefinition def = new ViewStore(wr.resolve("views")).get("merged").orElseThrow();
        assertNull(def.derivedSql(), "a merged (multi-source) view path is not single-SELECT expressible → null");
    }

    @Test
    void incrementalWatermarkOnAVarcharColumnIsNotTruncated() throws Exception {
        // task #11 — DuckDB answers max() on a Parquet VARCHAR column from its writer-truncated min/max
        // statistics ('2020-01-02' -> '2020-01-'); a truncated (prefix) watermark would re-admit already-seen
        // rows on the next run. The fix forces a scanned max for string columns.
        String dataDir = tmp.resolve("data").toString();
        String auditDir = tmp.resolve("audit").toString();
        seedTsFile(dataDir, "events", "a1", "(1,'2020-01-01'),(2,'2020-01-02')");
        FlowStore store = new FlowStore(tmp.resolve("flows"));
        store.write("inc_str", new FlowGraph("inc_str", true,
                List.of(FlowNode.of("src", "acquisition", Map.of("source_store", "events")),
                        new FlowNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(FlowEdge.data("src", "out"))));
        JobConfig r1 = new JobConfig("istr", JobType.FLOW, null, null, true, false,
                Map.of("flow", "inc_str", "data_dir", dataDir, "incremental_column", "ts", "batch_id", "i1"));
        new FlowJobRunner(r1, new BatchEventBus(), store, dataDir, auditDir).run();
        assertEquals(List.of(1, 2), readIds(dataDir, "rollup"), "run 1 reads the whole store");

        seedTsFile(dataDir, "events", "a2", "(3,'2020-01-03')");
        JobConfig r2 = new JobConfig("istr", JobType.FLOW, null, null, true, false,
                Map.of("flow", "inc_str", "data_dir", dataDir, "incremental_column", "ts", "batch_id", "i2"));
        new FlowJobRunner(r2, new BatchEventBus(), store, dataDir, auditDir).run();
        // truncated watermark '2020-01-' would re-admit ids 1,2 (lexically > the prefix) → [1,1,2,2,3];
        // the fix stores the true max '2020-01-02', so run 2 appends only id 3.
        assertEquals(List.of(1, 2, 3), readIds(dataDir, "rollup"), "run 2 appends only the row past the true watermark");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Write {@code (id,ts)} VARCHAR VALUES as a uniquely-named Parquet file under {@code <dataDir>/<store>/}. */
    private static void seedTsFile(String dataDir, String store, String file, String valuesSql) throws Exception {
        Path dir = Path.of(dataDir, store);
        Files.createDirectories(dir);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,ts)) TO '"
                    + dir.resolve(file + ".parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Execute {@code sql} on a fresh DuckDB and return the {@code id} column, ascending. */
    private static List<Integer> runIds(String sql) throws Exception {
        File db = DuckDbUtil.tempDbFile("dsql_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM (" + sql + ") q ORDER BY 1")) {
            List<Integer> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getInt(1));
            return out;
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Write {@code (id,amt)} VALUES as a Parquet file under {@code <dataDir>/<store>/} (the at-rest store). */
    private static void seedParquet(String dataDir, String store, String valuesSql) throws Exception {
        seedParquetFile(dataDir, store, "seed", valuesSql);
    }

    /** Like {@link #seedParquet} but with an explicit file stem, so a store can accumulate several files. */
    private static void seedParquetFile(String dataDir, String store, String file, String valuesSql) throws Exception {
        Path dir = Path.of(dataDir, store);
        Files.createDirectories(dir);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,amt)) TO '"
                    + dir.resolve(file + ".parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
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
