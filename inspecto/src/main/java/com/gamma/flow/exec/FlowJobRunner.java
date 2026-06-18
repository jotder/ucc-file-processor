package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowStore;
import com.gamma.flow.FlowStores;
import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.job.JobType;
import com.gamma.service.BatchEventBus;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/**
 * <b>T32 Phase A — run an authored {@code *_flow.toon} flow for real, as a {@link JobType#FLOW} job.</b>
 *
 * <p>An authored flow is <em>job-style</em> (§3.8, T23): it reads a {@code source_store} (data already
 * at rest), runs its {@code transform.*} nodes, and writes its sink {@code store}(s) — it is not a
 * re-acquisition (ingest is pipeline-exclusive). So rather than compile it back to a {@code PipelineConfig}
 * (which only round-trips lifted graphs, not UI-authored ones), this runner drives the production
 * {@link FlowExecutor} directly, hosted on the existing {@link com.gamma.job.JobService} scheduler — which
 * gives scheduling, audit, the deletion fence (T25) and {@code DbJobRunStore} reporting (T27) for free.
 *
 * <p>The run mirrors {@link com.gamma.enrich.EnrichmentEngine}'s read-view → transform → partitioned-write
 * → publish-event shape, on a throwaway DuckDB:
 * <ol>
 *   <li>seed the single {@code source_store} as a view ({@link SourceStoreReader});</li>
 *   <li>execute the {@code transform → sink} subgraph ({@link FlowExecutor#execute}) with a
 *       {@link PartitionSinkWriter} and a {@link BranchCommitCoordinator} (idempotent multi-branch commit,
 *       T11) — a flow job has no acquisition to finalise, so the source-finalisation step is a no-op;</li>
 *   <li>publish a chain {@link BatchEvent} so downstream {@code on_pipeline} jobs fire.</li>
 * </ol>
 *
 * <h3>Config ({@code *_job.toon})</h3>
 * <pre>
 * job:
 *   name: nightly_rollup
 *   type: flow                 # this runner
 *   flow: events_rollup        # authored flow id (FlowStore.get)
 *   cron: "0 2 * * *"          # OR on_pipeline: events_etl  (event)  OR manual (trigger API)
 *   data_dir: database         # optional — overrides the injected data root
 *   batch_id: ...              # optional — fixed batch id (idempotent re-run); default per-run timestamp
 * </pre>
 *
 * <p><b>Phase A scope:</b> a single {@code source_store}, persistent/materialized sinks, full-recompute
 * commit. Multi-{@code source_store} flows, {@code sink.view} byte-less stores, and incremental/watermark
 * re-runs are Phase C (see {@code docs/flow-live-execution-plan.md}).
 */
@PublicApi(since = "4.3.0")
public final class FlowJobRunner implements Job {

    private static final Logger log = LoggerFactory.getLogger(FlowJobRunner.class);
    private static final String SEED_VIEW = "flow_src";

    private final JobConfig cfg;
    private final BatchEventBus bus;
    private final FlowStore flowStore;
    private final String dataDir;
    private final String auditDir;

    /**
     * @param cfg       the job config ({@code flow} param = authored flow id)
     * @param bus       the batch-event bus for chain events
     * @param flowStore the authored-flow store ({@code <write-root>/flows}) to load the flow from
     * @param dataDir   the data root under which each store is a sub-directory (per-job {@code data_dir} overrides)
     * @param auditDir  the directory for the branch-commit log
     */
    public FlowJobRunner(JobConfig cfg, BatchEventBus bus, FlowStore flowStore,
                         String dataDir, String auditDir) {
        this.cfg = cfg;
        this.bus = bus;
        this.flowStore = flowStore;
        this.dataDir = dataDir;
        this.auditDir = auditDir;
    }

    @Override public String name() { return cfg.name(); }
    @Override public JobType type() { return JobType.FLOW; }

    @Override
    public JobResult run() throws Exception {
        String flowId = cfg.require("flow");
        FlowGraph g = flowStore.get(flowId).orElseThrow(() -> new IllegalArgumentException(
                "flow job '" + cfg.name() + "' references unknown flow '" + flowId + "'"));
        String dir = cfg.opt("data_dir", dataDir);
        String batchId = cfg.opt("batch_id", cfg.name().toLowerCase().replace(' ', '_')
                + "-" + System.currentTimeMillis());
        Seed seed = seedOf(g);

        long t0 = System.nanoTime();
        File db = DuckDbUtil.tempDbFile("flowjob_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            SourceStoreReader.registerView(conn, SEED_VIEW, dir, seed.store(), seed.format());

            PartitionSinkWriter writer = new PartitionSinkWriter(conn, dir,
                    cfg.name().toLowerCase().replace(' ', '_'));
            BranchCommitCoordinator coordinator = new BranchCommitCoordinator(new BranchCommitLog(
                    Path.of(auditDir).resolve(safe(flowId) + "_branch_commit_" + safe(batchId) + ".csv").toString()));

            FlowExecutor.execute(conn, g, seed.node(), SEED_VIEW, batchId, coordinator, writer, () -> {});

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            List<String> parts = writer.outputs().stream().map(PartitionOutput::partition).distinct().toList();
            bus.publish(new BatchEvent(cfg.name(), batchId, "SUCCESS", parts, writer.totalRows(), ms, 0));
            log.info("[FLOWJOB] {} ran flow '{}' (source_store '{}'): {} file(s), {} row(s) → {}",
                    cfg.name(), flowId, seed.store(), writer.outputs().size(), writer.totalRows(),
                    FlowStores.produced(g));
            return JobResult.ok(writer.outputs().size() + " file(s), " + writer.totalRows()
                    + " row(s) → store(s) " + FlowStores.produced(g), ms);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The seed: the single {@code source_store} node, its store, and its at-rest format. */
    private record Seed(String node, String store, String format) {}

    private static Seed seedOf(FlowGraph g) {
        List<FlowNode> sources = g.nodes().stream()
                .filter(n -> {
                    Object s = n.cfg(FlowStores.CONFIG_SOURCE_STORE);
                    return s != null && !s.toString().isBlank();
                })
                .toList();
        if (sources.isEmpty())
            throw new IllegalArgumentException("flow '" + g.name() + "' declares no '"
                    + FlowStores.CONFIG_SOURCE_STORE + "' — a flow job reads data at rest (§3.8)");
        if (sources.size() > 1)
            throw new IllegalArgumentException("flow '" + g.name() + "' declares " + sources.size()
                    + " source_store nodes; multi-store flow jobs are Phase C (T32)");
        FlowNode src = sources.get(0);
        Object fmt = src.cfg("format");
        return new Seed(src.id(), src.cfg(FlowStores.CONFIG_SOURCE_STORE).toString(),
                fmt == null || fmt.toString().isBlank() ? "PARQUET" : fmt.toString().toUpperCase());
    }

    /** Sanitise a string for use as a filename segment (the branch-commit log lives in the audit dir). */
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
