package com.gamma.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.report.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link JobType#REPORT} job: computes a report from {@link ReportService} on a schedule
 * and emits it as a single structured line on the {@code ucc.events} logger — a periodic,
 * correlatable snapshot to complement the on-demand Control API report endpoints (the live
 * delivery surface). The same aggregation backs both, so the scheduled snapshot and an API
 * call agree.
 *
 * <p>Param: {@code scope} — {@code status} (live snapshot, default) or {@code batch} /
 * {@code service} (historical batch-audit rollup).
 */
final class ReportJob implements Job {

    private static final Logger events = LoggerFactory.getLogger("ucc.events");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobConfig cfg;
    private final ReportService reports;

    ReportJob(JobConfig cfg, ReportService reports) {
        this.cfg = cfg;
        this.reports = reports;
    }

    @Override public String name() { return cfg.name(); }
    @Override public JobType type() { return JobType.REPORT; }

    @Override
    public JobResult run() throws Exception {
        String scope = cfg.opt("scope", "status").toLowerCase();
        long t0 = System.nanoTime();
        Object report = switch (scope) {
            case "status"                  -> reports.statusReport();
            case "batch", "service", "all" -> reports.serviceReport();
            default -> throw new IllegalArgumentException(
                    "report scope must be 'status' or 'batch', got '" + scope + "'");
        };
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "report");
        line.put("job", cfg.name());
        line.put("scope", scope);
        line.put("report", report);
        events.info(JSON.writeValueAsString(line));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        return JobResult.ok("report '" + scope + "' emitted to ucc.events", ms);
    }
}
