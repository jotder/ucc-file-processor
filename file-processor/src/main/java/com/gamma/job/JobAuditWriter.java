package com.gamma.job;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Append-only audit for {@link Job} executions: one growing {@code jobs_runs.csv} under a
 * configurable directory (default {@code jobs_audit/}). Unlike a batch commit log, a job
 * run isn't a recoverable unit, so a single durable CSV is the right grain — it's the
 * source the {@code /jobs/{name}/runs} endpoint and operators read.
 *
 * <p>{@link #record} is {@code synchronized} so concurrent jobs append whole rows.
 */
final class JobAuditWriter {

    private final String runsPath;

    JobAuditWriter(String auditDir) {
        Path dir = Paths.get(auditDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create jobs audit dir: " + auditDir, e);
        }
        this.runsPath = dir.resolve("jobs_runs.csv").toString();
    }

    synchronized void record(JobRun r) {
        boolean exists = new java.io.File(runsPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(runsPath, true))) {
            if (!exists)
                pw.println("run_id,job,type,trigger,start_time,end_time,status,duration_ms,message");
            pw.printf("%s,%s,%s,%s,%s,%s,%s,%d,\"%s\"%n",
                    r.runId(), r.job(), r.type(), r.trigger(), r.startTime(), r.endTime(),
                    r.status(), r.durationMs(), q(r.message()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String path() { return runsPath; }

    private static String q(String v) {
        return v == null ? "" : v.replace('"', '\'');
    }
}
