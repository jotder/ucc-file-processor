package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowStore;
import com.gamma.flow.FlowStores;
import com.gamma.flow.ViewDefinition;
import com.gamma.flow.ViewStore;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>seed each {@code source_store} as a view ({@link SourceStoreReader});</li>
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
 * <p><b>Scope (T32 Phase C):</b> multiple {@code source_store}s (each seeded as its own view; a
 * {@code transform.merge} joins/unions them); persistent/materialized sinks plus {@code sink.view} logical
 * stores (registered as a {@link com.gamma.flow.ViewDefinition}); full-recompute by default, or opt-in
 * incremental re-run via the {@code incremental_column} job param (single-source — reads only rows past the
 * stored watermark and appends). See {@code docs/flow-live-execution-plan.md}.
 */
@PublicApi(since = "4.3.0")
public final class FlowJobRunner implements Job {

    private static final Logger log = LoggerFactory.getLogger(FlowJobRunner.class);
    private static final String SEED_VIEW_PREFIX = "flow_src";

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
        List<Seed> seeds = seedsOf(g);
        String incCol = cfg.opt("incremental_column", "").trim();   // T32 Phase C — opt-in incremental re-run
        boolean incremental = !incCol.isBlank();
        if (incremental && seeds.size() > 1)
            throw new IllegalArgumentException("flow '" + g.name() + "' is incremental ('incremental_column') but "
                    + "declares " + seeds.size() + " source_stores; incremental flow jobs are single-source (T32)");
        FlowWatermarkStore watermarks = incremental ? new FlowWatermarkStore(Path.of(auditDir)) : null;

        long t0 = System.nanoTime();
        File db = DuckDbUtil.tempDbFile("flowjob_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            Map<String, String> seedViews = new LinkedHashMap<>();
            for (Seed seed : seeds) {                              // one view per source_store (multi-source, Phase C)
                String view = SEED_VIEW_PREFIX + "_" + safe(seed.node());
                String predicate = incremental
                        ? watermarks.get(flowId, seed.store())
                            .map(wm -> "\"" + incCol + "\" > '" + wm.replace("'", "''") + "'").orElse(null)
                        : null;
                SourceStoreReader.registerView(conn, view, dir, seed.store(), seed.format(), predicate);
                seedViews.put(seed.node(), view);
            }

            // incremental runs append (a run-unique base name so each increment is its own file); a full
            // recompute keeps a stable base name so a same-batch_id replay stays idempotent.
            String sinkBase = cfg.name().toLowerCase().replace(' ', '_') + (incremental ? "_" + safe(batchId) : "");
            PartitionSinkWriter writer = new PartitionSinkWriter(conn, dir, sinkBase);
            BranchCommitCoordinator coordinator = new BranchCommitCoordinator(new BranchCommitLog(
                    Path.of(auditDir).resolve(safe(flowId) + "_branch_commit_" + safe(batchId) + ".csv").toString()));

            FlowExecutor.execute(conn, g, seedViews, batchId, coordinator, writer, () -> {});

            if (incremental) advanceWatermarks(conn, watermarks, flowId, seeds, seedViews, incCol);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            List<String> parts = writer.outputs().stream().map(PartitionOutput::partition).distinct().toList();
            List<String> srcStores = seeds.stream().map(Seed::store).toList();
            registerViews(g, flowId, srcStores);                   // T32 Phase C — sink.view → durable definition
            bus.publish(new BatchEvent(cfg.name(), batchId, "SUCCESS", parts, writer.totalRows(), ms, 0));
            log.info("[FLOWJOB] {} ran flow '{}' (source_store(s) {}): {} file(s), {} row(s) → {}",
                    cfg.name(), flowId, srcStores, writer.outputs().size(), writer.totalRows(),
                    FlowStores.produced(g));
            return JobResult.ok(writer.outputs().size() + " file(s), " + writer.totalRows()
                    + " row(s) → store(s) " + FlowStores.produced(g), ms);
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** A seed: one {@code source_store} node, its store, and its at-rest format. */
    private record Seed(String node, String store, String format) {}

    /** Every {@code source_store} node in the flow (≥1); a {@code transform.merge} downstream joins/unions them. */
    private static List<Seed> seedsOf(FlowGraph g) {
        List<Seed> seeds = g.nodes().stream()
                .filter(n -> {
                    Object s = n.cfg(FlowStores.CONFIG_SOURCE_STORE);
                    return s != null && !s.toString().isBlank();
                })
                .map(n -> {
                    Object fmt = n.cfg("format");
                    return new Seed(n.id(), n.cfg(FlowStores.CONFIG_SOURCE_STORE).toString(),
                            fmt == null || fmt.toString().isBlank() ? "PARQUET" : fmt.toString().toUpperCase());
                })
                .toList();
        if (seeds.isEmpty())
            throw new IllegalArgumentException("flow '" + g.name() + "' declares no '"
                    + FlowStores.CONFIG_SOURCE_STORE + "' — a flow job reads data at rest (§3.8)");
        return seeds;
    }

    /**
     * T32 Phase C — register a durable {@link ViewDefinition} for each logical {@code sink.view} the flow
     * produces (those that {@link FlowStores.Produced#restsOnDisk() rest nothing}). Non-fatal: the data sinks
     * have already committed, so a registration failure is logged, not raised. Views land under
     * {@code <write-root>/views/} (sibling of the authored-flow store) for a KPI/report/alert API to bind to.
     */
    private void registerViews(FlowGraph g, String flowId, List<String> srcStores) {
        ViewStore views = new ViewStore(flowStore.root().resolveSibling("views"));
        String now = Instant.now().toString();
        for (FlowStores.Produced p : FlowStores.producedStores(g)) {
            if (p.restsOnDisk()) continue;     // persistent/materialized already wrote bytes
            try {
                views.write(new ViewDefinition(p.store(), flowId, srcStores, null, now));
                log.info("[FLOWJOB] registered logical view '{}' (flow '{}', source_store(s) {})",
                        p.store(), flowId, srcStores);
            } catch (Exception e) {
                log.warn("[FLOWJOB] could not register view '{}': {}", p.store(), e.getMessage());
            }
        }
    }

    /**
     * T32 Phase C — advance each source_store's high-watermark to the {@code max(incremental_column)} over the
     * rows just processed (the filtered seed view). {@code null} max = no new rows ⇒ keep the prior watermark.
     * Called only after the branch commit (crash-before-advance re-reads the increment, which the sink write
     * makes idempotent).
     */
    private static void advanceWatermarks(Connection conn, FlowWatermarkStore store, String flowId,
                                          List<Seed> seeds, Map<String, String> seedViews, String incCol)
            throws Exception {
        for (Seed seed : seeds) {
            String newMax = queryMaxAsText(conn, seedViews.get(seed.node()), incCol);
            if (newMax != null) store.put(flowId, seed.store(), newMax);
        }
    }

    /** {@code max(col)::VARCHAR} over {@code view}; {@code null} when the view is empty (no new rows this run). */
    private static String queryMaxAsText(Connection conn, String view, String col) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT max(\"" + col + "\")::VARCHAR FROM \"" + view + "\"")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Sanitise a string for use as a filename segment (the branch-commit log lives in the audit dir). */
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
