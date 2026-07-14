package com.gamma.service;

import com.gamma.etl.BatchEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.metrics.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Wires observability (M4) onto a running {@link CollectorService}: it subscribes to the
 * batch-commit {@link BatchEventBus} to record throughput / latency / error metrics,
 * registers scrape-time gauge collectors (inbox lag, committed batches, quarantine
 * depth), and emits one structured JSON event log per batch (correlated by
 * {@code run_id}/{@code batch_id}). Metrics land in the process-wide
 * {@link MetricRegistry#global()}, which the Control API exposes at {@code /metrics}.
 *
 * <p>The eager metrics (counters/histogram) come straight off the bus event; the
 * point-in-time gauges are computed lazily when {@code /metrics} is scraped, so they
 * reflect current state (e.g. how stale the inbox is right now) without a polling loop.
 */
public final class MetricsService {

    private static final Logger log    = LoggerFactory.getLogger(MetricsService.class);
    /** Dedicated logger for machine-readable batch events; route/ship separately if desired. */
    private static final Logger events = LoggerFactory.getLogger("inspecto.events");

    private final CollectorService svc;
    private final MetricRegistry reg;

    public MetricsService(CollectorService svc, MetricRegistry reg) {
        this.svc = svc;
        this.reg = reg;
    }

    /** Subscribe to the bus and register scrape-time gauge collectors. */
    public void start() {
        svc.eventBus().subscribe(this::onBatch);
        reg.addCollector(this::collectGauges);
        log.info("MetricsService started (metrics on the global registry)");
    }

    // ── eager metrics + structured log, per committed batch ──────────────────────

    private void onBatch(BatchEvent e) {
        Map<String, String> byPipeline = Map.of("pipeline", e.pipeline());
        reg.inc("inspecto_batches_total", "Terminal batches by pipeline and status",
                Map.of("pipeline", e.pipeline(), "status", e.status()));
        reg.observe("inspecto_batch_duration_seconds", "Batch wall time", byPipeline, e.durationMs() / 1000.0);
        if ("SUCCESS".equals(e.status())) {
            reg.inc("inspecto_output_rows_total", "Rows written by committed batches", byPipeline, e.outputRows());
            reg.inc("inspecto_partitions_written_total", "Output partitions written", byPipeline, e.partitions().size());
        }
        if (e.rejectedCount() > 0)
            reg.inc("inspecto_rejected_files_total", "Quarantined member files", byPipeline, e.rejectedCount());

        // structured, correlatable event line (run/batch id, status, rows, duration)
        events.info("{\"event\":\"batch\",\"pipeline\":\"{}\",\"batch_id\":\"{}\",\"status\":\"{}\","
                + "\"rows\":{},\"partitions\":{},\"rejected\":{},\"duration_ms\":{}}",
                e.pipeline(), e.batchId(), e.status(), e.outputRows(),
                e.partitions().size(), e.rejectedCount(), e.durationMs());
    }

    // ── scrape-time gauges (computed lazily on /metrics) ─────────────────────────

    private void collectGauges() {
        for (CollectorService.PipelineView pv : svc.pipelines()) {
            PipelineConfig cfg;
            try {
                cfg = svc.configFor(pv.name()).orElse(null);
            } catch (Exception ex) {
                continue;
            }
            if (cfg == null) continue;
            Map<String, String> labels = Map.of("pipeline", pv.name());
            reg.setGauge("inspecto_committed_batches", "Batches committed (durable)", labels, pv.committedBatches());
            reg.setGauge("inspecto_paused", "1 if the pipeline is paused", labels, pv.paused() ? 1 : 0);
            reg.setGauge("inspecto_quarantine_files", "Files currently in quarantine", labels,
                    svc.statusStore().quarantine(cfg).size());
            reg.setGauge("inspecto_inbox_oldest_seconds", "Age of the oldest unprocessed inbox file (lag)",
                    labels, oldestInboxAgeSeconds(cfg.dirs().poll()));
        }
    }

    /** Seconds since the oldest regular file under {@code pollDir} was modified; 0 if empty/absent. */
    private static double oldestInboxAgeSeconds(String pollDir) {
        if (pollDir == null || pollDir.isBlank()) return 0;
        Path root = Path.of(pollDir);
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> w = Files.walk(root)) {
            long oldest = w.filter(Files::isRegularFile)
                    .mapToLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); }
                                      catch (IOException e) { return Long.MAX_VALUE; } })
                    .min().orElse(0L);
            if (oldest == 0L || oldest == Long.MAX_VALUE) return 0;
            return Math.max(0, (System.currentTimeMillis() - oldest) / 1000.0);
        } catch (IOException e) {
            return 0;
        }
    }
}
