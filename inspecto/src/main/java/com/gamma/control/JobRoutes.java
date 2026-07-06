package com.gamma.control;

import com.gamma.pipeline.exec.DbProvenanceStore;
import com.gamma.job.DbJobRunStore;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
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
        // W5 async: poll one run by id (the id returned by the 202 trigger below). Single-segment after
        // /jobs/runs/, so it never collides with the exact /jobs/runs or the /jobs/{name}/runs history route.
        api.get("/jobs/runs/([^/]+)", (e, m) -> runById(api, ApiContext.name(m)));
        api.get("/jobs/([^/]+)/runs", (e, m) -> jobs(api).runsFor(ApiContext.name(m)));
        // Requires canOperateRuns (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/jobs/([^/]+)/trigger", ApiContext.withCapability("canOperateRuns", (e, m) -> triggerJob(api, e, ApiContext.name(m))));

        // ── data-plane provenance (T22, §11): per-(node, relationship) record counts of a past flow run,
        // for painting quantities onto the PipelineGraph edges (Sankey). 404 unless -Dprovenance.backend is set. ──
        api.get("/provenance", (e, m) -> provenanceData(api, ApiContext.query(e, "flow"), ApiContext.query(e, "batch")));
        api.get("/provenance/batches", (e, m) -> provenanceBatches(api, ApiContext.query(e, "flow"), ApiContext.query(e, "limit")));
    }

    /**
     * {@code POST /jobs/{name}/trigger} — fire a job (async; jobs already run off the request thread). On the
     * v1 surface returns {@code 202} + {@code {runId,...}} + a {@code Location} to poll; the legacy surface keeps
     * its unchanged {@code 200 {job,status:"triggered"}} body. 404 if no such job.
     */
    private Object triggerJob(ApiContext api, HttpExchange e, String name) throws IOException {
        String runId = jobs(api).triggerRun(name, ApiContext.query(e, "actor"))   // optional ?actor= attributes the fire (T32)
                .orElseThrow(() -> new ApiException(404, "no job named '" + name + "'"));
        if (ApiContext.v1(e)) {
            e.getResponseHeaders().set("Location", "/api/v1/jobs/runs/" + runId);
            return ApiContext.respondJson(e, 202, Map.of("runId", runId, "job", name, "status", "running"));
        }
        return Map.of("job", name, "status", "triggered");
    }

    /** {@code GET /jobs/runs/{runId}} — poll one run's status (W5); 404 once evicted or unknown. */
    private Object runById(ApiContext api, String runId) {
        JobRun r = jobs(api).runById(runId).orElseThrow(() -> new ApiException(404, "no run '" + runId + "'"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("job", r.job());
        m.put("type", r.type());
        m.put("trigger", r.trigger());
        m.put("status", r.status());
        m.put("startedAt", r.startTime());
        m.put("finishedAt", r.endTime());
        m.put("durationMs", r.durationMs());
        m.put("message", r.message());
        return m;
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
