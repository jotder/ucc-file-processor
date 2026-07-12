package com.gamma.control;

import com.gamma.job.JobService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET /health/details} (System Maintenance MNT-15): per-subsystem health for the bound space,
 * one level deeper than the bare {@code /health} liveness probe. Each subsystem reports
 * {@code UP} / {@code DOWN} / {@code NOT_CONFIGURED} plus a one-line detail; the overall status is
 * {@code DOWN} iff any subsystem is genuinely DOWN (an unconfigured optional subsystem is not a
 * failure). Checks are cheap and honest: presence, writability, and — for the DuckDB job-run
 * projection — one trivial query over its live connection to prove it answers. Unlike {@code /health}
 * this route is NOT on the public-path allowlist: it exposes operational detail (paths, counts), so
 * it rides the normal auth gate of the edition.
 */
final class HealthDetails {

    private HealthDetails() {}

    static Map<String, Object> of(ApiContext api) {
        Map<String, Object> subs = new LinkedHashMap<>();

        Path writeRoot = api.writeRoot();
        if (writeRoot == null) {
            subs.put("configStore", sub("NOT_CONFIGURED", "no write root (filesystem writes disabled)"));
        } else if (Files.isDirectory(writeRoot) && Files.isWritable(writeRoot)) {
            subs.put("configStore", sub("UP", writeRoot.toString()));
        } else {
            subs.put("configStore", sub("DOWN", "write root missing or not writable: " + writeRoot));
        }

        Path dataRoot = api.dataRoot();
        if (dataRoot != null && Files.isDirectory(dataRoot)) {
            subs.put("dataStore", sub("UP", dataRoot.toString()));
        } else {
            // A data root is created lazily on first write — absence is not a failure signal.
            subs.put("dataStore", sub("NOT_CONFIGURED",
                    dataRoot == null ? "no data root bound" : "not present yet (created on first write): " + dataRoot));
        }

        subs.put("pipelines", sub("UP", api.service().pipelines().size() + " registered"));

        Optional<JobService> jobs = api.service().jobService();
        if (jobs.isEmpty()) {
            subs.put("scheduler", sub("NOT_CONFIGURED", "no jobs registered"));
            subs.put("jobRunsProjection", sub("NOT_CONFIGURED", "no jobs registered"));
        } else {
            var views = jobs.get().jobs();
            long cron = views.stream().filter(v -> v.cron() != null && !v.cron().isBlank()).count();
            subs.put("scheduler", sub("UP", views.size() + " job(s), " + cron + " cron-scheduled"));
            var runStore = jobs.get().runStore();
            if (runStore.isEmpty()) {
                subs.put("jobRunsProjection", sub("NOT_CONFIGURED", "-Djobs.backend not set"));
            } else {
                try {
                    runStore.get().metrics(null);   // one trivial query proves the live connection answers
                    subs.put("jobRunsProjection", sub("UP", "queryable"));
                } catch (RuntimeException e) {
                    subs.put("jobRunsProjection", sub("DOWN", String.valueOf(e.getMessage())));
                }
            }
        }

        boolean down = subs.values().stream()
                .anyMatch(s -> "DOWN".equals(((Map<?, ?>) s).get("status")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", down ? "DOWN" : "UP");
        out.put("subsystems", subs);
        return out;
    }

    private static Map<String, Object> sub(String status, String detail) {
        return Map.of("status", status, "detail", detail);
    }
}
