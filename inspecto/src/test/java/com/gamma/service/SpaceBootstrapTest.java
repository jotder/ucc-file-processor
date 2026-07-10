package com.gamma.service;

import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.event.EventLog;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.pipeline.PipelineEdge;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStore;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Builds one space's runtime from its own {@code config/} directory (the per-space analogue of ServiceBootstrap). */
class SpaceBootstrapTest {

    @Test
    void buildsAnEmptySpaceWithManifestAndPerSpaceLedger(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("space-a");
        Files.createDirectories(base.resolve("config"));     // empty config tree — a freshly created space
        Files.writeString(base.resolve("space.toon"),
                "display_name: \"Space A\"\ndescription: \"a test space\"\ncreated_at: \"2026-06-22\"\n");

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            assertEquals("space-a", ctx.id().value(), "id is the directory name");
            assertEquals("Space A", ctx.manifest().displayName(), "display name from space.toon");
            assertEquals("a test space", ctx.manifest().description());
            assertNotNull(ctx.service(), "an empty space still builds a service (no System.exit)");
            assertTrue(ctx.service().pipelines().isEmpty(), "no pipelines in an empty config dir");

            // The space's acquisition ledger is registered under its id — visible only under that space's MDC.
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            try {
                AcquisitionLedger scoped = AcquisitionLedgers.shared();
                assertNotNull(scoped, "space-a has its own registered ledger");
            } finally {
                MDC.remove(EventLog.SPACE_MDC_KEY);
            }
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-a");
            try { AcquisitionLedgers.use(null); }   // drop the test space's ledger entry
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    @Test
    void flowJobResolvesAFlowAuthoredUnderConfigFlows(@TempDir Path tmp) throws Exception {
        // The flow is written exactly where the HTTP flow CRUD writes it (writeRoot == the space's
        // config/ → config/flows/); a pipeline-type job booted from the same space must resolve it —
        // the two stores diverged once (config/flows vs a sibling flows/) and jobs failed with
        // "references unknown flow".
        Path base = tmp.resolve("space-fl");
        Files.createDirectories(base.resolve("config"));
        seedParquet(base.resolve("data"), "events", "(1,150),(2,50),(3,200)");
        PipelineStore authored = new PipelineStore(base.resolve("config").resolve("flows"));
        authored.write("evt_rollup", new PipelineGraph("evt_rollup", true,
                List.of(PipelineNode.of("src", "acquisition", Map.of("source_store", "events")),
                        PipelineNode.of("flt", "transform.filter", Map.of("where", "amt >= 100")),
                        new PipelineNode("out", "sink.persistent", "Rollup", null, Map.of("store", "rollup"), null)),
                List.of(PipelineEdge.data("src", "flt"), PipelineEdge.data("flt", "out"))));
        Files.writeString(base.resolve("config").resolve("rollup_job.toon"), """
                job:
                  name: space_rollup
                  type: pipeline
                  flow: evt_rollup
                """);

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            JobService js = ctx.service().jobService().orElseThrow();
            assertTrue(js.trigger("space_rollup"), "the flow job is registered and triggerable");
            JobRun run = await(() -> js.lastRunOf("space_rollup").orElse(null));
            assertEquals("SUCCESS", run.status(),
                    "the job resolves the flow from config/flows/: " + run.message());
            assertTrue(Files.exists(base.resolve("data").resolve("rollup")), "the flow wrote its sink store");
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-fl");
            try { AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    /** Write {@code (id,amt)} VALUES as a Parquet file under {@code <dataDir>/<store>/} (an at-rest source store). */
    private static void seedParquet(Path dataDir, String store, String valuesSql) throws Exception {
        Path d = dataDir.resolve(store);
        Files.createDirectories(d);
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES " + valuesSql + ") t(id,amt)) TO '"
                    + d.resolve("seed.parquet").toString().replace("\\", "/") + "' (FORMAT PARQUET)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    /** Poll until the supplier yields a non-null run (or 10s elapse). */
    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 10s");
        return r;
    }

    @Test
    void manifestDefaultsToIdWhenNoSpaceToon(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("space-b");
        Files.createDirectories(base.resolve("config"));     // no space.toon

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            assertEquals("space-b", ctx.id().value());
            assertEquals("space-b", ctx.manifest().displayName(), "display name defaults to the id");
            assertEquals("", ctx.manifest().description());
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "space-b");
            try { AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }
}
