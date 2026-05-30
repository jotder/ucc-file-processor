package com.gamma.report;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.service.SourceService;
import com.gamma.service.StatusStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the raw audit the {@link StatusStore} exposes into operator-facing
 * <b>reports</b> — the summarised view the Control API serves (v2.8.0). The store
 * returns per-row audit ({@code batches}, {@code files}, {@code quarantine}, …); this
 * service rolls those rows up into two shapes:
 *
 * <ul>
 *   <li>a live <b>status</b> snapshot — per-pipeline current state (paused, committed
 *       batch count, quarantined files, last batch outcome) plus a service rollup; and</li>
 *   <li>a historical <b>batch-audit report</b> — counts, rows in/out, rejects, bytes,
 *       durations and error rate derived from the batch audit rows, per pipeline and
 *       service-wide.</li>
 * </ul>
 *
 * <p>It reads through the same {@link StatusStore} the rest of the platform uses, so it
 * works unchanged over the file backend or the DB backend (M5). It is read-only and
 * holds no state of its own.
 */
@PublicApi(since = "2.8.0")
public final class ReportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SourceService service;

    public ReportService(SourceService service) {
        this.service = service;
    }

    // ── DTOs (serialise straight to JSON) ────────────────────────────────────────

    /** One pipeline's current state for the live status snapshot. */
    public record PipelineStatus(String pipeline, boolean paused, int committedBatches,
                                 int quarantineFiles, String lastBatchId,
                                 String lastBatchStatus, String lastBatchTime) {}

    /** Service-wide live snapshot: per-pipeline state plus rollup counts. */
    public record StatusReport(String generatedAt, int pipelineCount, int pausedCount,
                               long totalCommittedBatches, long totalQuarantineFiles,
                               List<PipelineStatus> pipelines) {}

    /** Historical batch-audit rollup for one pipeline. */
    public record BatchAuditReport(String pipeline, long totalBatches, long success, long failed,
                                   double errorRate, long totalInputRows, long totalOutputRows,
                                   long totalRejectedFiles, long totalOutputFiles, long totalOutputBytes,
                                   long avgDurationMs, long maxDurationMs,
                                   String firstBatchTime, String lastBatchTime) {}

    /** Service-wide batch-audit report: per-pipeline rollups plus service totals. */
    public record ServiceReport(String generatedAt, long totalBatches, long success, long failed,
                                double errorRate, long totalOutputRows,
                                List<BatchAuditReport> pipelines) {}

    // ── live status snapshot ─────────────────────────────────────────────────────

    /** Build the live status snapshot across all registered pipelines. */
    public StatusReport statusReport() {
        StatusStore store = service.statusStore();
        List<PipelineStatus> rows = new ArrayList<>();
        int paused = 0;
        long committed = 0, quarantined = 0;
        for (SourceService.PipelineView v : service.pipelines()) {
            PipelineConfig cfg = service.configFor(v.name()).orElse(null);
            int quarantine = 0;
            String lastId = "", lastStatus = "", lastTime = "";
            if (cfg != null) {
                quarantine = store.quarantine(cfg).size();
                List<Map<String, String>> batches = store.batches(cfg);
                if (!batches.isEmpty()) {
                    Map<String, String> last = batches.get(batches.size() - 1);   // newest run last
                    lastId     = last.getOrDefault("batch_id", "");
                    lastStatus = last.getOrDefault("status", "");
                    lastTime   = last.getOrDefault("end_time", last.getOrDefault("start_time", ""));
                }
            }
            if (v.paused()) paused++;
            committed   += v.committedBatches();
            quarantined += quarantine;
            rows.add(new PipelineStatus(v.name(), v.paused(), v.committedBatches(),
                    quarantine, lastId, lastStatus, lastTime));
        }
        return new StatusReport(now(), rows.size(), paused, committed, quarantined, rows);
    }

    // ── historical batch-audit report ────────────────────────────────────────────

    /** Roll up the batch audit for one pipeline by name. */
    public BatchAuditReport batchReport(String pipelineName) {
        PipelineConfig cfg = service.configFor(pipelineName).orElseThrow(
                () -> new IllegalArgumentException("no pipeline named '" + pipelineName + "'"));
        return rollUp(pipelineName, service.statusStore().batches(cfg));
    }

    /** Service-wide batch-audit report: every registered pipeline plus totals. */
    public ServiceReport serviceReport() {
        List<BatchAuditReport> perPipeline = new ArrayList<>();
        long batches = 0, success = 0, failed = 0, outRows = 0;
        for (SourceService.PipelineView v : service.pipelines()) {
            PipelineConfig cfg = service.configFor(v.name()).orElse(null);
            if (cfg == null) continue;
            BatchAuditReport r = rollUp(v.name(), service.statusStore().batches(cfg));
            perPipeline.add(r);
            batches += r.totalBatches();
            success += r.success();
            failed  += r.failed();
            outRows += r.totalOutputRows();
        }
        double errorRate = batches == 0 ? 0.0 : round(failed / (double) batches);
        return new ServiceReport(now(), batches, success, failed, errorRate, outRows, perPipeline);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static BatchAuditReport rollUp(String pipeline, List<Map<String, String>> rows) {
        long total = rows.size(), success = 0, failed = 0;
        long inRows = 0, outRows = 0, rejected = 0, outFiles = 0, outBytes = 0;
        long durSum = 0, durMax = 0;
        String first = "", last = "";
        for (Map<String, String> r : rows) {
            String status = r.getOrDefault("status", "");
            if ("SUCCESS".equals(status)) success++;
            else if (!status.isBlank()) failed++;
            inRows   += asLong(r.get("total_input_rows"));
            outRows  += asLong(r.get("total_output_rows"));
            rejected += asLong(r.get("rejected_count"));
            outFiles += asLong(r.get("output_file_count"));
            outBytes += asLong(r.get("total_output_bytes"));
            long dur = asLong(r.get("duration_ms"));
            durSum += dur;
            if (dur > durMax) durMax = dur;
            String start = r.getOrDefault("start_time", "");
            String end   = r.getOrDefault("end_time", "");
            if (first.isEmpty() && !start.isEmpty()) first = start;
            if (!end.isEmpty()) last = end;
        }
        long avgDur = total == 0 ? 0 : durSum / total;
        double errorRate = total == 0 ? 0.0 : round(failed / (double) total);
        return new BatchAuditReport(pipeline, total, success, failed, errorRate,
                inRows, outRows, rejected, outFiles, outBytes, avgDur, durMax, first, last);
    }

    private static long asLong(String v) {
        if (v == null || v.isBlank()) return 0L;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 0L; }
    }

    private static double round(double d) {
        return Math.round(d * 10000.0) / 10000.0;
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }
}
