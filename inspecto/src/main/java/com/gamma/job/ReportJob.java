package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.DatasetRelation;
import com.gamma.query.MeasureCompiler;
import com.gamma.query.QueryExecutor;
import com.gamma.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link JobType#REPORT} job: computes a report on a schedule and emits it as a single structured
 * line on the {@code inspecto.events} logger — plus, since BI-4, optionally <b>delivers</b> it: with
 * {@code out_dir} the report is rendered to a timestamped artifact file (a directory being the first
 * delivery destination — point it at a mounted share to hand off) and a {@link EventType#REPORT_READY}
 * event is emitted, which the notification layer routes to the configured external channels (webhook
 * POST; SMTP text with the artifact path — attachments are a known SMTP-channel limitation).
 *
 * <h3>Scopes</h3>
 * <ul>
 *   <li>{@code status} (default) — the live snapshot from {@link ReportService}.</li>
 *   <li>{@code batch} / {@code service} / {@code all} — the historical batch-audit rollup.</li>
 *   <li>{@code dataset} (BI-4 export) — a headless BI query over a Dataset: params {@code dataset}
 *       (component id, required), {@code measures} (comma-separated {@code agg(field)}/{@code count};
 *       absent = raw rows), {@code group_by} (comma-separated columns), {@code limit} (default 10000).
 *       Renders CSV by default ({@code format: png} renders a table-image snapshot, capped at
 *       {@link TablePngRenderer#MAX_ROWS} rows); reports render JSON.</li>
 * </ul>
 *
 * <p>Params: {@code scope}, {@code out_dir}, {@code format} ({@code json} | {@code csv} | {@code png}), and the
 * dataset-scope params above.
 */
final class ReportJob implements Job {

    private static final Logger events = LoggerFactory.getLogger("inspecto.events");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final JobConfig cfg;
    private final ReportService reports;
    /** The space's data dir (for a dataset export's {@code physicalRef}); {@code null} degrades to view-backed only. */
    private final String dataDir;

    ReportJob(JobConfig cfg, ReportService reports) {
        this(cfg, reports, null);
    }

    ReportJob(JobConfig cfg, ReportService reports, String dataDir) {
        this.cfg = cfg;
        this.reports = reports;
        this.dataDir = dataDir;
    }

    @Override public String name() { return cfg.name(); }
    @Override public String type() { return "report"; }

    @Override
    public JobResult run() throws Exception {
        return execute(null);   // legacy no-ctx path — no Run Artifact recorder available
    }

    @Override
    public JobResult run(JobContext ctx) throws Exception {
        return execute(ctx.artifacts());   // JobService invokes this — records the delivered file (R7)
    }

    private JobResult execute(ArtifactRecorder artifacts) throws Exception {
        String scope = cfg.opt("scope", "status").toLowerCase();
        long t0 = System.nanoTime();

        Object report;
        List<Map<String, Object>> rows = null;   // dataset scope: tabular result for CSV rendering
        switch (scope) {
            case "status"                  -> report = reports.statusReport();
            case "batch", "service", "all" -> report = reports.serviceReport();
            case "dataset" -> { rows = datasetRows(); report = rows; }
            default -> throw new IllegalArgumentException(
                    "report scope must be 'status', 'batch' or 'dataset', got '" + scope + "'");
        }

        // The structured log snapshot stays — delivery is additive (BI-4).
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "report");
        line.put("job", cfg.name());
        line.put("scope", scope);
        line.put("report", report);
        events.info(JSON.writeValueAsString(line));

        String delivered = deliver(scope, report, rows, artifacts);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return JobResult.ok("report '" + scope + "' emitted to inspecto.events"
                + (delivered != null ? " and delivered to " + delivered : ""), ms);
    }

    /**
     * Render + write the artifact when {@code out_dir} is set, record it as a {@code file} Run Artifact
     * (when a recorder is present, so {@code GET /jobs/{name}/runs/{runId}/artifacts/report/content} can
     * serve it), emit REPORT_READY; returns the path or null.
     */
    private String deliver(String scope, Object report, List<Map<String, Object>> rows, ArtifactRecorder artifacts)
            throws Exception {
        String outDir = cfg.opt("out_dir", null);
        if (outDir == null) return null;
        String format = cfg.opt("format", rows != null ? "csv" : "json").toLowerCase();
        Path dir = Path.of(outDir);
        Files.createDirectories(dir);
        Path artifact = dir.resolve(cfg.name() + "_" + TS.format(LocalDateTime.now())
                + ("csv".equals(format) ? ".csv" : "png".equals(format) ? ".png" : ".json"));
        if ("csv".equals(format)) {
            if (rows == null) throw new IllegalArgumentException(
                    "format csv requires scope dataset (rollup reports render as json)");
            Files.writeString(artifact, toCsv(rows));
        } else if ("png".equals(format)) {
            if (rows == null) throw new IllegalArgumentException(
                    "format png requires scope dataset (rollup reports render as json)");
            TablePngRenderer.render(cfg.name(), rows, artifact);
        } else {
            Files.writeString(artifact, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
        if (artifacts != null) artifacts.file("report", artifact, Files.size(artifact));
        int rowCount = rows != null ? rows.size() : -1;
        EventLog.current().emit(Event.builder(EventType.REPORT_READY)
                .source(ReportJob.class.getName())
                .message("Report '" + cfg.name() + "' (" + scope + ") ready: " + artifact
                        + (rowCount >= 0 ? " (" + rowCount + " row(s))" : ""))
                .attr("job", cfg.name())
                .attr("scope", scope)
                .attr("path", artifact.toString()));
        return artifact.toString();
    }

    /** The dataset-scope export rows: a headless BI query compiled from this job's params (BI-4/BI-7). */
    private List<Map<String, Object>> datasetRows() throws Exception {
        String wr = System.getProperty("assist.write.root");
        if (wr == null || wr.isBlank())
            throw new IllegalStateException("scope dataset needs -Dassist.write.root (the component registry)");
        Path writeRoot = Path.of(wr);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dataset", cfg.require("dataset"));
        List<Map<String, Object>> measures = new ArrayList<>();
        for (String m : split(cfg.opt("measures", ""))) {
            if ("count".equals(m)) { measures.add(Map.of("agg", "count")); continue; }
            int p = m.indexOf('(');
            if (p < 0 || !m.endsWith(")"))
                throw new IllegalArgumentException("measure must be count or agg(field), got '" + m + "'");
            measures.add(Map.of("agg", m.substring(0, p), "field", m.substring(p + 1, m.length() - 1)));
        }
        if (!measures.isEmpty()) body.put("measures", measures);
        List<String> groupBy = split(cfg.opt("group_by", ""));
        if (!groupBy.isEmpty()) body.put("groupBy", groupBy);
        body.put("limit", Integer.parseInt(cfg.opt("limit", "10000")));

        MeasureCompiler.Spec spec = measures.isEmpty() && groupBy.isEmpty()
                ? null   // raw export: SELECT * over the dataset (no aggregation)
                : MeasureCompiler.parse(body, 10_000, 100_000);
        String sql = spec != null ? MeasureCompiler.compile(spec)
                : "SELECT * FROM \"" + cfg.require("dataset").replace("\"", "\"\"") + "\" LIMIT "
                        + Integer.parseInt(cfg.opt("limit", "10000"));

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Object> dataset = store.get("dataset", cfg.require("dataset"))
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset '" + cfg.require("dataset") + "'"));
        String relationSql = DatasetRelation.relationSql(dataset,
                (dataDir == null || dataDir.isBlank()) ? null : Path.of(dataDir),
                new ViewStore(writeRoot.resolve("views")));

        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                cfg.require("dataset"), relationSql, sql,
                Integer.parseInt(cfg.opt("limit", "10000")), 0, List.of(), List.of()));
        return r.rows();
    }

    /** Rows → CSV: header = union of row keys in first-seen order; RFC-ish quoting. */
    private static String toCsv(List<Map<String, Object>> rows) {
        Set<String> header = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) header.addAll(r.keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", header.stream().map(ReportJob::csv).toList())).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>(header.size());
            for (String h : header) cells.add(csv(r.get(h) == null ? "" : String.valueOf(r.get(h))));
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        return (s.contains(",") || s.contains("\"") || s.contains("\n"))
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static List<String> split(String csvList) {
        List<String> out = new ArrayList<>();
        for (String s : csvList.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
