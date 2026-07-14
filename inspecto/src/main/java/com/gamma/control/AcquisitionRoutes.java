package com.gamma.control;

import com.gamma.metrics.MetricRegistry;

import java.util.Set;

/**
 * Acquisition / Sources observability routes: a flat view of every pipeline's source acquisition
 * config ({@code /collectors}) and a JSON snapshot of the acquisition metrics ({@code /metrics/acquisition},
 * complementing the text-only Prometheus {@code /metrics}). Extracted verbatim from {@link ControlApi}.
 */
final class AcquisitionRoutes implements RouteModule {

    /** Acquisition metric names exposed (as JSON) by {@code GET /metrics/acquisition}. */
    private static final Set<String> ACQ_METRICS = Set.of(
            "inspecto_files_discovered_total", "inspecto_files_downloaded_total", "inspecto_downloads_failed_total",
            "inspecto_post_actions_failed_total", "inspecto_watermark_skipped_total", "inspecto_bytes_transferred_total",
            "inspecto_fetch_seconds", "inspecto_active_connections", "inspecto_files_waiting_stability");

    @Override
    public void register(ApiContext api) {
        api.get("/collectors", (e, m) -> api.service().collectors());
        // ACQ-6 push discovery: an external system (S3 event notification, upload script, upstream job)
        // tells Inspecto a file has landed on a source, triggering an immediate scan cycle instead of
        // waiting out the poll interval. Same operate capability + lock-safe trigger as /runs/{x}/trigger;
        // the scan itself decides what is actually new (dedup/stability), so a spurious notify is harmless.
        api.post("/collectors/([^/]+)/notify", ApiContext.withCapability("canOperateRuns", (e, m) ->
                notifySource(api, e, ApiContext.name(m))));
        api.get("/metrics/acquisition", (e, m) -> acquisitionMetrics());
    }

    /**
     * {@code POST /collectors/{id}/notify} — fire the pipeline owning source {@code id}. v1: {@code 202} +
     * {@code {runId,…}} + a {@code Location} to poll (async, off the ingest lock — mirrors
     * {@code POST /runs/{name}/trigger}); legacy: synchronous {@code 200} run result. 404 for an unknown
     * source id. The request body is ignored: a notify is a hint that something arrived, and the triggered
     * cycle re-discovers authoritatively.
     */
    private Object notifySource(ApiContext api, com.sun.net.httpserver.HttpExchange e, String sourceId)
            throws java.io.IOException {
        String pipeline = api.service().pipelineForSourceId(sourceId)
                .orElseThrow(() -> new ApiException(404, "no source '" + sourceId + "'"));
        if (ApiContext.v1(e)) {
            String runId = api.service().triggerRunAsync(pipeline, "notify")
                    .orElseThrow(() -> new ApiException(404, "no source '" + sourceId + "'"));
            e.getResponseHeaders().set("Location", "/api/v1/runs/runs/" + runId);
            return ApiContext.respondJson(e, 202, java.util.Map.of(
                    "runId", runId, "source", sourceId, "pipeline", pipeline, "status", "running"));
        }
        return api.service().runPipeline(pipeline)
                .orElseThrow(() -> new ApiException(404, "no source '" + sourceId + "'"));
    }

    /** {@code GET /metrics/acquisition} — the acquisition counters/gauges/histogram as JSON (UI dashboard). */
    private Object acquisitionMetrics() {
        return MetricRegistry.global().snapshot(ACQ_METRICS::contains);
    }
}
