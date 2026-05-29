package com.gamma.enrich;

import com.gamma.etl.CommitLog;
import com.gamma.etl.PartitionOutput;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Persists <b>run-level audit + lineage</b> for Stage-2 enrichment recomputes — the
 * concern deferred since M0, now that recomputes are orchestrated (event / schedule / CLI).
 * It mirrors the Stage-1 {@link com.gamma.etl.BatchAuditWriter} idea (append-only CSVs plus
 * a durable {@link CommitLog}) but at the enrichment grain: one <em>run</em> per recompute,
 * and one lineage row per written output partition file.
 *
 * <h3>Artifacts</h3>
 * Written under {@link #auditDir(EnrichmentConfig)} (a {@code _audit} sibling of the output
 * root by default — never inside the partitioned tree, so it can't collide with output globs),
 * with stable (non-run-timestamped) names so each job has a single growing ledger keyed by
 * {@code run_id}:
 * <ul>
 *   <li><b>{@code <job>_enrich_runs.csv}</b> — one row per recompute (SUCCESS and FAILED):
 *       trigger, reason, input scope, output partition/file counts, rows, bytes, duration.</li>
 *   <li><b>{@code <job>_enrich_lineage.csv}</b> — one row per written output partition file
 *       (run_id, partition, file, bytes) for successful runs.</li>
 *   <li><b>{@code <job>_enrich_commits.log}</b> — a durable, fsync'd {@link CommitLog} of
 *       successful runs (the "did this recompute finish" ledger; survives a crash).</li>
 * </ul>
 *
 * <p>{@link #record} is {@code synchronized} so a run's rows are written contiguously even if
 * two different jobs flush at once (a single job's recomputes are already serialised by the
 * orchestrator's per-job lock).
 */
public final class EnrichmentAuditWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RUN_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final String runsPath;
    private final String lineagePath;
    private final CommitLog commitLog;

    /** Open (creating the directory) the audit ledger for {@code job} under {@code auditDir}. */
    public EnrichmentAuditWriter(String auditDir, String job) {
        String base = job.toLowerCase().replace(' ', '_');
        Path dir = Paths.get(auditDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create enrichment audit dir: " + auditDir, e);
        }
        this.runsPath    = dir.resolve(base + "_enrich_runs.csv").toString();
        this.lineagePath = dir.resolve(base + "_enrich_lineage.csv").toString();
        this.commitLog   = new CommitLog(dir.resolve(base + "_enrich_commits.log").toString());
    }

    /** Default audit directory for a job: a {@code _audit} sibling of the output root. */
    public static String auditDir(EnrichmentConfig cfg) {
        return cfg.output().database() + "_audit";
    }

    /** Wall-clock now, formatted for an audit timestamp column. */
    public static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** A run id stem (timestamp); the orchestrator appends a per-job sequence for uniqueness. */
    public static String runStamp() {
        return LocalDateTime.now().format(RUN_TS);
    }

    /** One enrichment-run summary row. */
    public record RunRow(String runId, String job, String trigger, String reason, String scope,
                         int inputPartitionCount, String startTime, String endTime, String status,
                         int outputPartitionCount, int outputFileCount, long totalOutputRows,
                         long totalOutputBytes, long durationMs, String error) {}

    /**
     * Append a run's summary + lineage, and (on SUCCESS) record it in the durable commit log.
     * The commit-log write is last, so a line there implies the CSV rows are written too.
     *
     * @param outputs the written partition files (empty for a failed/no-op run)
     */
    public synchronized void record(RunRow run, List<PartitionOutput> outputs) {
        appendRun(run);
        appendLineage(run.runId(), run.job(), outputs);
        if ("SUCCESS".equals(run.status())) {
            commitLog.record(run.endTime(), run.runId(), run.job(), run.status(),
                    run.inputPartitionCount(), run.outputFileCount(),
                    run.totalOutputRows(), run.totalOutputBytes());
        }
    }

    private void appendRun(RunRow r) {
        boolean exists = new java.io.File(runsPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(runsPath, true))) {
            if (!exists)
                pw.println("run_id,job,trigger,reason,scope,input_partition_count,start_time,end_time,"
                        + "status,output_partition_count,output_file_count,total_output_rows,"
                        + "total_output_bytes,duration_ms,error");
            pw.printf("%s,%s,%s,\"%s\",\"%s\",%d,%s,%s,%s,%d,%d,%d,%d,%d,\"%s\"%n",
                    r.runId(), r.job(), r.trigger(), q(r.reason()), q(r.scope()),
                    r.inputPartitionCount(), r.startTime(), r.endTime(), r.status(),
                    r.outputPartitionCount(), r.outputFileCount(), r.totalOutputRows(),
                    r.totalOutputBytes(), r.durationMs(), q(r.error()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendLineage(String runId, String job, List<PartitionOutput> outputs) {
        if (outputs == null || outputs.isEmpty()) return;
        boolean exists = new java.io.File(lineagePath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(lineagePath, true))) {
            if (!exists) pw.println("run_id,job,partition,output_file,bytes");
            for (PartitionOutput o : outputs)
                pw.printf("%s,%s,%s,\"%s\",%d%n",
                        runId, job, o.partition(), q(o.outputFile()), o.bytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Make a value safe inside a double-quoted CSV field. */
    private static String q(String v) {
        return v == null ? "" : v.replace('"', '\'');
    }
}
