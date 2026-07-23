package com.gamma.job;

import com.gamma.signal.Severity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code storage_trend} maintenance task (System Maintenance COULD tier — growth-trend analysis +
 * archive recommendations). Read-only analysis over the {@code maintenance_storage} sample series that
 * {@code storage_report} accumulates (one row per axis per run): fits a simple two-point growth rate
 * (bytes/day) per axis and for the whole space over the most recent {@code window_days} (default 30),
 * projects when the total will cross {@code warn_bytes}, and surfaces the fastest-growing axes as
 * archive candidates. Emits a {@code maintenance.storage.trend} WARNING when the projected breach is
 * within {@code warn_days} (default 14), or the threshold is already exceeded. Nothing is mutated —
 * findings go to the Run Log message and, on a projected breach, one signal.
 *
 * <p>A two-point (earliest→latest in-window) slope, not a regression: cheap, honest, and adequate for a
 * housekeeping heuristic. A short sample span yields a noisy rate — {@code window_days} plus roughly
 * daily {@code storage_report} sampling is the intended cadence. Fewer than two samples in the window is
 * reported as insufficient history (fail-soft SUCCESS), so a freshly-enabled space is never an error.
 */
final class StorageTrendTask {

    private static final String STORAGE_CATALOG = "maintenance_storage";
    private static final long DAY_MS = 86_400_000L;

    private StorageTrendTask() {}

    /** One axis's growth over the window: its latest observed size and its bytes/day slope. */
    private record AxisTrend(String axis, long currentBytes, double bytesPerDay) {}

    static JobResult run(JobConfig cfg, String dataDir, JobContext ctx) throws Exception {
        long t0 = System.nanoTime();
        int windowDays = Integer.parseInt(cfg.opt("window_days", "30"));
        long warnBytes = Long.parseLong(cfg.opt("warn_bytes", "0"));   // 0 = no threshold
        int warnDays = Integer.parseInt(cfg.opt("warn_days", "14"));
        int top = Integer.parseInt(cfg.opt("top", "5"));

        if (dataDir == null || dataDir.isBlank())
            return JobResult.ok("storage_trend: no data root configured — no sample history to analyse", 0L);
        Path storeDir = Path.of(dataDir).resolve(STORAGE_CATALOG);
        if (!Files.isDirectory(storeDir) || !hasParquet(storeDir))
            return JobResult.ok("storage_trend: no storage_report history yet (" + storeDir
                    + ") — run storage_report first", (System.nanoTime() - t0) / 1_000_000L);

        long cutoffMs = Instant.now().minus(Duration.ofDays(windowDays)).toEpochMilli();
        String glob = "'" + storeDir.toAbsolutePath().toString().replace('\\', '/').replace("'", "''")
                + "/*.parquet'";

        List<long[]> totals = new ArrayList<>();   // {createdMs, totalBytes} per sample, ascending
        List<AxisTrend> axisTrends = new ArrayList<>();
        com.gamma.util.DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT created_ms, CAST(sum(bytes) AS BIGINT) FROM read_parquet("
                    + glob + ") WHERE created_ms >= " + cutoffMs + " GROUP BY created_ms ORDER BY created_ms")) {
                while (rs.next()) totals.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
            if (totals.size() < 2)
                return JobResult.ok("storage_trend: insufficient history (" + totals.size()
                        + " sample(s) in the last " + windowDays + "d); need >= 2 to project a trend",
                        (System.nanoTime() - t0) / 1_000_000L);
            double spanDays = spanDays(totals);
            try (ResultSet rs = st.executeQuery("SELECT axis, arg_min(bytes, created_ms), "
                    + "arg_max(bytes, created_ms) FROM read_parquet(" + glob + ") WHERE created_ms >= "
                    + cutoffMs + " GROUP BY axis")) {
                while (rs.next()) {
                    long first = rs.getLong(2), last = rs.getLong(3);
                    axisTrends.add(new AxisTrend(rs.getString(1), last, (last - first) / spanDays));
                }
            }
        }

        double spanDays = spanDays(totals);
        long earliestTotal = totals.get(0)[1];
        long latestTotal = totals.get(totals.size() - 1)[1];
        double totalPerDay = (latestTotal - earliestTotal) / spanDays;
        axisTrends.sort(Comparator.comparingDouble(AxisTrend::bytesPerDay).reversed());

        // Projection of the total against warn_bytes.
        boolean breach = false;
        long etaDays = -1;
        if (warnBytes > 0) {
            if (latestTotal >= warnBytes) { breach = true; etaDays = 0; }
            else if (totalPerDay > 0) {
                etaDays = Math.round((warnBytes - latestTotal) / totalPerDay);
                breach = etaDays <= warnDays;
            }
        }

        // Fastest-growing axes = archive candidates (positive growth only, top N).
        StringBuilder growth = new StringBuilder();
        int shown = 0;
        for (AxisTrend a : axisTrends) {
            if (a.bytesPerDay() <= 0 || shown >= top) break;
            growth.append(shown == 0 ? "" : ", ").append(a.axis())
                    .append("(+").append(Math.round(a.bytesPerDay())).append("b/day)");
            shown++;
        }
        String recommend = shown == 0 ? "no axis is growing" : growth.toString();

        String projection = warnBytes <= 0 ? "no warn_bytes threshold set"
                : latestTotal >= warnBytes ? "ALREADY OVER warn_bytes=" + warnBytes
                : totalPerDay <= 0 ? "not growing — no projected breach of warn_bytes=" + warnBytes
                : "projected to reach warn_bytes=" + warnBytes + " in ~" + etaDays + "d"
                        + (etaDays <= warnDays ? " (within warn_days=" + warnDays + ")" : "");

        if (ctx != null && breach)
            ctx.signals().emit("maintenance.storage.trend", Severity.WARN, Map.of(
                    "currentBytes", latestTotal, "bytesPerDay", Math.round(totalPerDay),
                    "etaDays", etaDays, "warnBytes", warnBytes,
                    "topAxis", axisTrends.isEmpty() ? "-" : axisTrends.get(0).axis()));

        String msg = "storage_trend: total " + latestTotal + "b, " + (totalPerDay >= 0 ? "+" : "")
                + Math.round(totalPerDay) + "b/day over "
                + String.format(Locale.ROOT, "%.1f", spanDays) + "d window (" + totals.size()
                + " samples); archive candidates: " + recommend + "; " + projection;
        return JobResult.ok(msg, (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Window span in days between the earliest and latest sample (floored just above zero). */
    private static double spanDays(List<long[]> totals) {
        long span = totals.get(totals.size() - 1)[0] - totals.get(0)[0];
        return Math.max(span, 1L) / (double) DAY_MS;
    }

    /** True when the catalog dir holds at least one Parquet sample (a glob over an empty dir throws). */
    private static boolean hasParquet(Path storeDir) throws java.io.IOException {
        try (var s = Files.list(storeDir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        }
    }
}
