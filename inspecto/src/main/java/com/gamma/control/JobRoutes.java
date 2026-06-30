package com.gamma.control;

import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobService;

import java.util.Map;

/**
 * Config-driven job routes ({@code /jobs*}, v2.8.0) plus the data-plane provenance reads
 * ({@code /provenance*}, T22/§11) which are projected by the same {@link JobService}. Covers the
 * job registry (list / per-job run history / manual trigger) and the optional DuckDB reporting
 * projection ({@code /jobs/metrics|runs|failures}, T27, 404 unless {@code -Djobs.backend} is set).
 * Extracted verbatim from {@link ControlApi}: identical routes, order, statuses and shapes.
 */
final class JobRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/jobs", (e, m) -> jobs(api).jobs());
        // T27 job-execution reporting (DuckDB projection; 404 unless -Djobs.backend is set). Fixed
        // sub-paths, registered before the /jobs/{name}/runs regex (single-segment, so no collision).
        api.get("/jobs/metrics", (e, m) -> jobRunStore(api).metrics(ApiContext.query(e, "job")));
        api.get("/jobs/runs", (e, m) -> jobRunStore(api).recentRuns(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50), ApiContext.query(e, "job")));
        api.get("/jobs/failures", (e, m) -> jobRunStore(api).failureTrend(ApiContext.parseIntOr(ApiContext.query(e, "days"), 30)));
        api.get("/jobs/([^/]+)/runs", (e, m) -> jobs(api).runsFor(ApiContext.name(m)));
        api.post("/jobs/([^/]+)/trigger", (e, m) -> {
            if (!jobs(api).trigger(ApiContext.name(m), ApiContext.query(e, "actor")))   // optional ?actor= attributes the manual fire (T32)
                throw new ApiException(404, "no job named '" + ApiContext.name(m) + "'");
            return Map.of("job", ApiContext.name(m), "status", "triggered");
        });

        // ── data-plane provenance (T22, §11): per-(node, relationship) record counts of a past flow run,
        // for painting quantities onto the PipelineGraph edges (Sankey). 404 unless -Dprovenance.backend is set. ──
        api.get("/provenance", (e, m) -> provenanceData(api, ApiContext.query(e, "flow"), ApiContext.query(e, "batch")));
        api.get("/provenance/batches", (e, m) -> provenanceBatches(api, ApiContext.query(e, "flow"), ApiContext.query(e, "limit")));
    }

    /**
     * {@code GET /provenance?flow=&batch=} — the per-(node, relationship) record counts of one flow run (T22).
     * A consumer paints each {@code (nodeId, rel)} onto its outgoing {@code PipelineGraph} edge as the Sankey weight.
     * 400 if either param is missing, 404 when no provenance backend is configured.
     */
    private Object provenanceData(ApiContext api, String flow, String batch) {
        if (flow == null || flow.isBlank() || batch == null || batch.isBlank())
            throw new ApiException(400, "both 'flow' and 'batch' query params are required");
        return provenanceStore(api).query(flow, batch);
    }

    /** {@code GET /provenance/batches?flow=&limit=} — recent runs of a flow (newest first) to pick one to inspect. */
    private Object provenanceBatches(ApiContext api, String flow, String limit) {
        if (flow == null || flow.isBlank())
            throw new ApiException(400, "the 'flow' query param is required");
        return provenanceStore(api).batches(flow, ApiContext.parseIntOr(limit, 20));
    }

    /** The DuckDB data-plane provenance store (T21/T22), or a 404 when no backend is configured (-Dprovenance.backend). */
    private DbProvenanceStore provenanceStore(ApiContext api) {
        return jobs(api).provenanceStore().orElseThrow(() -> new ApiException(404,
                "provenance DB not enabled (set -Dprovenance.backend=duckdb)"));
    }

    /** The job registry, or a 404 when no jobs are registered on this service. */
    private JobService jobs(ApiContext api) {
        return api.service().jobService().orElseThrow(() -> new ApiException(404, "no jobs registered"));
    }

    /** The DuckDB job-run reporting store (T27), or a 404 when no backend is configured (-Djobs.backend). */
    private DbJobRunStore jobRunStore(ApiContext api) {
        return jobs(api).runStore().orElseThrow(() -> new ApiException(404,
                "job reporting DB not enabled (set -Djobs.backend=duckdb)"));
    }
}
