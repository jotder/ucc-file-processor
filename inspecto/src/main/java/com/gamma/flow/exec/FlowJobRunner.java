package com.gamma.flow.exec;

import com.gamma.api.PublicApi;
import com.gamma.etl.BatchEvent;
import com.gamma.etl.PartitionOutput;
import com.gamma.flow.FlowEdge;
import com.gamma.flow.FlowGraph;
import com.gamma.flow.FlowNode;
import com.gamma.flow.FlowRel;
import com.gamma.flow.FlowStore;
import com.gamma.flow.FlowStores;
import com.gamma.flow.ViewDefinition;
import com.gamma.flow.ViewStore;
import com.gamma.job.Job;
import com.gamma.job.JobConfig;
import com.gamma.job.JobResult;
import com.gamma.job.JobType;
import com.gamma.service.BatchEventBus;
import com.gamma.sql.SqlViews;
import com.gamma.util.DuckDbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final DbProvenanceStore provenance;   // T21 — nullable; default-off unless -Dprovenance.backend set

    /** As {@link #FlowJobRunner(JobConfig, BatchEventBus, FlowStore, String, String, DbProvenanceStore)} with no provenance store. */
    public FlowJobRunner(JobConfig cfg, BatchEventBus bus, FlowStore flowStore,
                         String dataDir, String auditDir) {
        this(cfg, bus, flowStore, dataDir, auditDir, null);
    }

    /**
     * @param cfg        the job config ({@code flow} param = authored flow id)
     * @param bus        the batch-event bus for chain events
     * @param flowStore  the authored-flow store ({@code <write-root>/flows}) to load the flow from
     * @param dataDir    the data root under which each store is a sub-directory (per-job {@code data_dir} overrides)
     * @param auditDir   the directory for the branch-commit log
     * @param provenance the data-plane provenance store (T21), or {@code null} to not record per-edge counts
     */
    public FlowJobRunner(JobConfig cfg, BatchEventBus bus, FlowStore flowStore,
                         String dataDir, String auditDir, DbProvenanceStore provenance) {
        this.cfg = cfg;
        this.bus = bus;
        this.flowStore = flowStore;
        this.dataDir = dataDir;
        this.auditDir = auditDir;
        this.provenance = provenance;
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
        // T32 follow-up — incremental is per-source: each source_store carries its own watermark (keyed by
        // store) and is filtered + advanced independently below, so multi-source incremental works. It requires
        // the incremental_column to exist in every source_store (e.g. a union of like-shaped stores, or sources
        // that all carry the same event-time column).
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

            // T20/T21 — collect per-(node, relationship) record counts during the walk (counts must be taken
            // while the scratch relations are live) and persist them as this run's data-plane provenance.
            String runTs = Instant.now().toString();
            List<ProvenanceRow> provRows = new ArrayList<>();
            FlowExecutor.ProvenanceCollector collector = provenance == null
                    ? FlowExecutor.ProvenanceCollector.NONE
                    : (nodeId, rel, rowCount) -> provRows.add(new ProvenanceRow(flowId, batchId, nodeId, rel, rowCount, runTs));

            FlowExecutor.execute(conn, g, seedViews, batchId, coordinator, writer, () -> {}, collector);

            if (provenance != null) provenance.record(provRows);

            if (incremental) advanceWatermarks(conn, watermarks, flowId, seeds, seedViews, incCol);

            long ms = (System.nanoTime() - t0) / 1_000_000L;
            List<String> parts = writer.outputs().stream().map(PartitionOutput::partition).distinct().toList();
            List<String> srcStores = seeds.stream().map(Seed::store).toList();
            registerViews(g, flowId, srcStores, dir);              // T32 Phase C — sink.view → durable definition
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
    private void registerViews(FlowGraph g, String flowId, List<String> srcStores, String dir) {
        ViewStore views = new ViewStore(flowStore.root().resolveSibling("views"));
        String now = Instant.now().toString();
        for (FlowStores.Produced p : FlowStores.producedStores(g)) {
            if (p.restsOnDisk()) continue;     // persistent/materialized already wrote bytes
            String derivedSql = deriveViewSql(g, p.node(), dir).orElse(null);   // single SELECT when expressible
            try {
                views.write(new ViewDefinition(p.store(), flowId, srcStores, derivedSql, now));
                log.info("[FLOWJOB] registered logical view '{}' (flow '{}', source_store(s) {}){}",
                        p.store(), flowId, srcStores, derivedSql == null ? "" : " with derived_sql");
            } catch (Exception e) {
                log.warn("[FLOWJOB] could not register view '{}': {}", p.store(), e.getMessage());
            }
        }
    }

    /**
     * T32 follow-up — best-effort {@code derived_sql} for a {@code sink.view}: if the view is fed by a
     * <b>single</b> source_store through a <b>linear</b> path of simple nodes
     * ({@code filter}/{@code map}/{@code select}/{@code derive}), fold that path into one SELECT over the source
     * read so a consumer can query the view directly. Returns empty for a branched / merged / multi-source /
     * complex path — the view then stays a re-run-the-flow definition ({@code derived_sql} null).
     */
    private static Optional<String> deriveViewSql(FlowGraph g, String viewNodeId, String dir) {
        Map<String, FlowNode> byId = g.byId();
        List<FlowNode> chain = new ArrayList<>();       // transforms between source and view, view-first
        String cur = viewNodeId;
        Set<String> seen = new HashSet<>();
        String sourceStore = null;
        String sourceFmt = "PARQUET";
        while (true) {
            List<FlowEdge> inbound = g.edgesTo(cur).stream().filter(e -> FlowRel.DATA.equals(e.rel())).toList();
            if (inbound.size() != 1) return Optional.empty();    // not a single linear data input (branch/merge/none)
            String prev = inbound.get(0).from();
            if (!seen.add(prev)) return Optional.empty();         // cycle guard
            FlowNode pn = byId.get(prev);
            if (pn == null) return Optional.empty();
            Object ss = pn.cfg(FlowStores.CONFIG_SOURCE_STORE);
            if (ss != null && !ss.toString().isBlank()) {         // reached the source — stop
                sourceStore = ss.toString();
                Object fmt = pn.cfg("format");
                if (fmt != null && !fmt.toString().isBlank()) sourceFmt = fmt.toString().toUpperCase();
                break;
            }
            chain.add(pn);                                        // an intermediate transform to fold
            cur = prev;
        }
        String glob = dir.replace("\\", "/") + "/" + sourceStore + "/**/*." + SqlViews.ext(sourceFmt);
        String sql = "SELECT * FROM " + SqlViews.reader(sourceFmt, glob, true);
        for (int i = chain.size() - 1; i >= 0; i--) {            // fold in source→view order
            Optional<String> step = RowShaper.toSelect(chain.get(i), sql);
            if (step.isEmpty()) return Optional.empty();          // a non-simple node on the path
            sql = step.get();
        }
        return Optional.of(sql);
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

    /**
     * {@code max(col)::VARCHAR} over {@code view}; {@code null} when the view is empty (no new rows this run).
     *
     * <p><b>Parquet string-statistics guard (task #11):</b> DuckDB answers {@code max()} on a Parquet
     * <em>VARCHAR</em> column from the column's min/max statistics, which the Parquet writer <em>truncates</em>
     * (e.g. {@code '2020-01-02'} → {@code '2020-01-'}). A truncated watermark is a prefix (smaller), so the next
     * run's {@code col > 'wm'} predicate would re-admit already-seen rows. We defeat the stat pushdown for a
     * string column with a computed expression ({@code col || ''}) so the max is the true scanned value; numeric
     * and temporal columns have exact stats, so they keep native {@code max()} (correct ordering — a lexical max
     * over an integer column would be wrong).
     */
    private static String queryMaxAsText(Connection conn, String view, String col) throws Exception {
        String q = "\"" + col + "\"";
        String expr = isVarcharColumn(conn, view, col) ? "max(" + q + " || '')" : "max(" + q + ")";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + expr + "::VARCHAR FROM \"" + view + "\"")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Whether {@code col} reads back as a DuckDB {@code VARCHAR} in {@code view} (empty view ⇒ false). */
    private static boolean isVarcharColumn(Connection conn, String view, String col) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT any_value(typeof(\"" + col + "\")) FROM \"" + view + "\"")) {
            return rs.next() && "VARCHAR".equalsIgnoreCase(rs.getString(1));
        }
    }

    /** Sanitise a string for use as a filename segment (the branch-commit log lives in the audit dir). */
    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
