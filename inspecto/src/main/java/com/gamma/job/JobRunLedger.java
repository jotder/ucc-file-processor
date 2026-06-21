package com.gamma.job;

import com.gamma.util.BoundedHistory;
import com.gamma.util.CsvLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The run journal for {@link JobService}: the durable append-only audit ({@code jobs_runs.csv}),
 * the bounded in-memory history the Control API serves, and the optional DuckDB run projection
 * (T27, {@code null} when no backend is configured). {@link #record} writes all three; the audit
 * file is also read back at startup for misfire/catch-up ({@link #lastStartTimes}), since the
 * {@link CsvLedger} itself is write-only.
 *
 * <p>The logger keeps {@code JobService}'s category so audit-failure warnings are unchanged from
 * when this code lived there.
 */
final class JobRunLedger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_HISTORY = 50;

    /** Append-only run audit ({@code jobs_runs.csv}) — a job run isn't a recoverable unit,
     *  so a single durable CSV is the right grain (no commit log). */
    private final CsvLedger<JobRun> audit;
    /** The audit file path — read back at startup for misfire/catch-up (T26); the CsvLedger is write-only. */
    private final Path auditFile;
    /** Optional DuckDB projection of job runs for reporting (T27); {@code null} when no backend is configured. */
    private final DbJobRunStore jobRunStore;
    private final Map<String, BoundedHistory<JobRun>> history = new ConcurrentHashMap<>();

    JobRunLedger(String auditDir, DbJobRunStore jobRunStore) {
        this.audit = openAudit(auditDir);
        this.auditFile = Paths.get(auditDir).resolve("jobs_runs.csv");
        this.jobRunStore = jobRunStore;
    }

    /** Create the audit dir and open the {@code jobs_runs.csv} ledger (was JobAuditWriter). */
    private static CsvLedger<JobRun> openAudit(String auditDir) {
        Path dir = Paths.get(auditDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create jobs audit dir: " + auditDir, e);
        }
        return new CsvLedger<>(dir.resolve("jobs_runs.csv").toString(),
                "run_id,job,type,trigger,start_time,end_time,status,duration_ms,message",
                r -> String.format("%s,%s,%s,%s,%s,%s,%s,%d,\"%s\"",
                        r.runId(), r.job(), r.type(), r.trigger(), r.startTime(), r.endTime(),
                        r.status(), r.durationMs(), CsvLedger.q(r.message())));
    }

    /** Record one run: durable audit, bounded in-memory history, and the optional DuckDB projection. */
    void record(JobRun run) {
        try {
            audit.append(run);
        } catch (Exception e) {
            log.warn("Could not write job audit for {}: {}", run.job(), e.getMessage());
        }
        history.computeIfAbsent(run.job(), k -> new BoundedHistory<>(MAX_HISTORY)).add(run);
        if (jobRunStore != null) jobRunStore.record(run);   // T27: durable, queryable projection
    }

    /** Recent run history (newest first) for one job; empty if unknown or never run. */
    List<JobRun> runsFor(String name) {
        BoundedHistory<JobRun> hist = history.get(name);
        return hist == null ? List.of() : hist.all();
    }

    /** The most recent run of a job, or {@code null} if unknown / never run. */
    JobRun lastRun(String name) {
        BoundedHistory<JobRun> hist = history.get(name);
        return hist == null ? null : hist.latest().orElse(null);
    }

    /**
     * Latest {@code start_time} per job from the audit CSV (empty when absent) — the catch-up baseline.
     * Reads the durable file directly because the {@link CsvLedger} is write-only.
     */
    Map<String, LocalDateTime> lastStartTimes() {
        Map<String, LocalDateTime> out = new LinkedHashMap<>();
        if (auditFile == null || !Files.exists(auditFile)) return out;
        try {
            List<String> lines = Files.readAllLines(auditFile);
            for (int i = 1; i < lines.size(); i++) {                      // row 0 is the header
                String[] f = lines.get(i).split(",", -1);                 // job + start_time are comma-free fields
                if (f.length < 5) continue;
                try {
                    LocalDateTime start = LocalDateTime.parse(f[4], TS);
                    out.merge(f[1], start, (a, b) -> b.isAfter(a) ? b : a);
                } catch (RuntimeException ignore) { /* skip a malformed row */ }
            }
        } catch (IOException e) {
            log.warn("Could not read job audit for catch-up ({}): {}", auditFile, e.getMessage());
        }
        return out;
    }

    /** The DuckDB job-run projection for reporting (T27), or empty when no backend is configured. */
    Optional<DbJobRunStore> runStore() {
        return Optional.ofNullable(jobRunStore);
    }

    @Override
    public void close() {
        if (jobRunStore != null) jobRunStore.close();
    }
}
