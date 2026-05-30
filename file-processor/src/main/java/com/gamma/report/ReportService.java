package com.gamma.report;

import com.gamma.api.PublicApi;
import com.gamma.etl.PipelineConfig;
import com.gamma.service.EnrichmentService;
import com.gamma.service.SourceService;
import com.gamma.service.StatusStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>The batch-audit and enrichment reports accept an optional {@link Window} (v2.10.0) —
 * an inclusive {@code [from, to]} date range that scopes the rollup to rows in that period
 * — and report duration <b>percentiles</b> (p50/p95/p99) alongside average and max, so a
 * tail-latency spike isn't hidden by the mean.
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

    /** Historical batch-audit rollup for one pipeline ({@code windowFrom}/{@code windowTo} echo the applied range, blank = unbounded). */
    public record BatchAuditReport(String pipeline, long totalBatches, long success, long failed,
                                   double errorRate, long totalInputRows, long totalOutputRows,
                                   long totalRejectedFiles, long totalOutputFiles, long totalOutputBytes,
                                   long avgDurationMs, long maxDurationMs,
                                   long p50DurationMs, long p95DurationMs, long p99DurationMs,
                                   String firstBatchTime, String lastBatchTime,
                                   String windowFrom, String windowTo) {}

    /** Service-wide batch-audit report: per-pipeline rollups plus service totals (+ service-wide duration percentiles). */
    public record ServiceReport(String generatedAt, long totalBatches, long success, long failed,
                                double errorRate, long totalOutputRows,
                                long p50DurationMs, long p95DurationMs, long p99DurationMs,
                                String windowFrom, String windowTo,
                                List<BatchAuditReport> pipelines) {}

    /** Historical run-audit rollup for one Stage-2 enrichment job (mirrors the batch report). */
    public record EnrichmentRunReport(String job, long totalRuns, long success, long failed,
                                      double errorRate, long totalOutputRows, long totalOutputFiles,
                                      long totalOutputBytes, long avgDurationMs, long maxDurationMs,
                                      long p50DurationMs, long p95DurationMs, long p99DurationMs,
                                      String firstRunTime, String lastRunTime,
                                      String windowFrom, String windowTo) {}

    /**
     * An inclusive {@code [from, to]} filter on an audit row's {@code start_time} (v2.10.0).
     * Either bound may be blank = unbounded; a date-only upper bound ({@code yyyy-MM-dd}) is
     * widened to cover the whole day. Audit timestamps use the {@code yyyy-MM-dd HH:mm:ss}
     * form, which sorts lexicographically, so the filter is a plain string compare — no
     * parsing, identical over the file and DB backends.
     */
    public record Window(String from, String to) {
        /** The unbounded window — every row matches. */
        public static final Window ALL = new Window("", "");

        /** Build from raw (possibly null/blank) bounds; widens a date-only {@code to} to end-of-day. */
        public static Window of(String from, String to) {
            String lo = from == null ? "" : from.trim();
            String hi = to == null ? "" : to.trim();
            if (hi.length() == 10) hi = hi + " 23:59:59";   // whole-day inclusive
            return new Window(lo, hi);
        }

        public boolean bounded() { return !from.isEmpty() || !to.isEmpty(); }

        /** Does an audit-row timestamp fall in the window? An undated row passes only when unbounded. */
        public boolean contains(String ts) {
            if (ts == null || ts.isEmpty()) return !bounded();
            if (!from.isEmpty() && ts.compareTo(from) < 0) return false;
            if (!to.isEmpty()   && ts.compareTo(to)   > 0) return false;
            return true;
        }
    }

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

    /** Roll up the batch audit for one pipeline by name (unbounded window). */
    public BatchAuditReport batchReport(String pipelineName) {
        return batchReport(pipelineName, Window.ALL);
    }

    /** Roll up the batch audit for one pipeline by name, scoped to {@code window}. */
    public BatchAuditReport batchReport(String pipelineName, Window window) {
        PipelineConfig cfg = service.configFor(pipelineName).orElseThrow(
                () -> new IllegalArgumentException("no pipeline named '" + pipelineName + "'"));
        return rollUp(pipelineName, service.statusStore().batches(cfg), window);
    }

    /** Service-wide batch-audit report: every registered pipeline plus totals (unbounded window). */
    public ServiceReport serviceReport() {
        return serviceReport(Window.ALL);
    }

    /** Service-wide batch-audit report scoped to {@code window}, with service-wide duration percentiles. */
    public ServiceReport serviceReport(Window window) {
        List<BatchAuditReport> perPipeline = new ArrayList<>();
        List<Long> allDurations = new ArrayList<>();
        long batches = 0, success = 0, failed = 0, outRows = 0;
        for (SourceService.PipelineView v : service.pipelines()) {
            PipelineConfig cfg = service.configFor(v.name()).orElse(null);
            if (cfg == null) continue;
            List<Map<String, String>> rows = service.statusStore().batches(cfg);
            BatchAuditReport r = rollUp(v.name(), rows, window);
            perPipeline.add(r);
            batches += r.totalBatches();
            success += r.success();
            failed  += r.failed();
            outRows += r.totalOutputRows();
            for (Map<String, String> row : rows)
                if (window.contains(row.getOrDefault("start_time", "")))
                    allDurations.add(asLong(row.get("duration_ms")));
        }
        double errorRate = batches == 0 ? 0.0 : round(failed / (double) batches);
        return new ServiceReport(now(), batches, success, failed, errorRate, outRows,
                percentile(allDurations, 50), percentile(allDurations, 95), percentile(allDurations, 99),
                window.from(), window.to(), perPipeline);
    }

    // ── enrichment run-audit report (v2.9.0) ─────────────────────────────────────

    /**
     * Roll up the Stage-2 run audit for one enrichment job by name — the same shape as the
     * Stage-1 batch report, computed over the {@code <job>_enrich_runs.csv} ledger the
     * orchestrator persists.
     *
     * @throws IllegalArgumentException if no enrichment is registered, or no job by that name
     */
    public EnrichmentRunReport enrichmentReport(String jobName) {
        return enrichmentReport(jobName, Window.ALL);
    }

    /** Roll up the Stage-2 run audit for one enrichment job, scoped to {@code window}. */
    public EnrichmentRunReport enrichmentReport(String jobName, Window window) {
        EnrichmentService es = service.enrichmentService().orElseThrow(
                () -> new IllegalArgumentException("no enrichment jobs registered"));
        return rollUpEnrichment(jobName, es.runs(jobName), window);   // runs(...) throws on unknown job
    }

    private static EnrichmentRunReport rollUpEnrichment(String job, List<Map<String, String>> rows, Window window) {
        long total = 0, success = 0, failed = 0;
        long outRows = 0, outFiles = 0, outBytes = 0, durSum = 0, durMax = 0;
        List<Long> durs = new ArrayList<>();
        String first = "", last = "";
        for (Map<String, String> r : rows) {
            if (!window.contains(r.getOrDefault("start_time", ""))) continue;
            total++;
            String status = r.getOrDefault("status", "");
            if ("SUCCESS".equals(status)) success++;
            else if (!status.isBlank()) failed++;
            outRows  += asLong(r.get("total_output_rows"));
            outFiles += asLong(r.get("output_file_count"));
            outBytes += asLong(r.get("total_output_bytes"));
            long dur = asLong(r.get("duration_ms"));
            durSum += dur;
            durs.add(dur);
            if (dur > durMax) durMax = dur;
            String start = r.getOrDefault("start_time", "");
            String end   = r.getOrDefault("end_time", "");
            if (first.isEmpty() && !start.isEmpty()) first = start;
            if (!end.isEmpty()) last = end;
        }
        long avgDur = total == 0 ? 0 : durSum / total;
        double errorRate = total == 0 ? 0.0 : round(failed / (double) total);
        return new EnrichmentRunReport(job, total, success, failed, errorRate,
                outRows, outFiles, outBytes, avgDur, durMax,
                percentile(durs, 50), percentile(durs, 95), percentile(durs, 99),
                first, last, window.from(), window.to());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static BatchAuditReport rollUp(String pipeline, List<Map<String, String>> rows, Window window) {
        long total = 0, success = 0, failed = 0;
        long inRows = 0, outRows = 0, rejected = 0, outFiles = 0, outBytes = 0;
        long durSum = 0, durMax = 0;
        List<Long> durs = new ArrayList<>();
        String first = "", last = "";
        for (Map<String, String> r : rows) {
            if (!window.contains(r.getOrDefault("start_time", ""))) continue;
            total++;
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
            durs.add(dur);
            if (dur > durMax) durMax = dur;
            String start = r.getOrDefault("start_time", "");
            String end   = r.getOrDefault("end_time", "");
            if (first.isEmpty() && !start.isEmpty()) first = start;
            if (!end.isEmpty()) last = end;
        }
        long avgDur = total == 0 ? 0 : durSum / total;
        double errorRate = total == 0 ? 0.0 : round(failed / (double) total);
        return new BatchAuditReport(pipeline, total, success, failed, errorRate,
                inRows, outRows, rejected, outFiles, outBytes, avgDur, durMax,
                percentile(durs, 50), percentile(durs, 95), percentile(durs, 99),
                first, last, window.from(), window.to());
    }

    /** Nearest-rank percentile (p in {@code [0,100]}) of {@code values}; 0 when empty. */
    private static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) return 0L;
        List<Long> s = new ArrayList<>(values);
        Collections.sort(s);
        int rank = (int) Math.ceil(p / 100.0 * s.size());
        int idx = Math.min(Math.max(rank, 1), s.size()) - 1;
        return s.get(idx);
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
