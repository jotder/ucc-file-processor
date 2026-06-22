package com.gamma.control;

import com.gamma.metrics.MetricRegistry;

import java.util.Set;

/**
 * Acquisition / Sources observability routes: a flat view of every pipeline's source acquisition
 * config ({@code /sources}) and a JSON snapshot of the acquisition metrics ({@code /metrics/acquisition},
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
        api.get("/sources", (e, m) -> api.service().sources());
        api.get("/metrics/acquisition", (e, m) -> acquisitionMetrics());
    }

    /** {@code GET /metrics/acquisition} — the acquisition counters/gauges/histogram as JSON (UI dashboard). */
    private Object acquisitionMetrics() {
        return MetricRegistry.global().snapshot(ACQ_METRICS::contains);
    }
}
