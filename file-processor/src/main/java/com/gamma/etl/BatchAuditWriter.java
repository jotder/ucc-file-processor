package com.gamma.etl;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Appends one batch's audit to three run-scoped CSVs, structured for a future
 * bulk RDBMS load (all joined by {@code batch_id}):
 * <ul>
 *   <li><b>status</b> (batch_file): one row per member (surviving or rejected)</li>
 *   <li><b>batches</b>: one row per batch</li>
 *   <li><b>lineage</b>: the (input → output) count matrix</li>
 * </ul>
 *
 * <p>{@link #flush} is {@code synchronized} so each batch's rows are written
 * contiguously even when multiple batches finish concurrently.
 */
public final class BatchAuditWriter {

    private final String statusPath;
    private final String batchesPath;
    private final String lineagePath;
    private final CommitLog commitLog;   // null when no commit-log path is configured
    private Consumer<BatchEvent> commitListener;   // null = no event emission

    /** Back-compat: audit CSVs only, no durable commit log. */
    public BatchAuditWriter(String statusPath, String batchesPath, String lineagePath) {
        this(statusPath, batchesPath, lineagePath, null);
    }

    /**
     * @param commitLogPath path to the durable append-only commit log
     *                      ({@code cfg.dirs().commitLogPath()}); {@code null} disables it
     */
    public BatchAuditWriter(String statusPath, String batchesPath, String lineagePath,
                            String commitLogPath) {
        this.statusPath  = statusPath;
        this.batchesPath = batchesPath;
        this.lineagePath = lineagePath;
        this.commitLog   = (commitLogPath != null && !commitLogPath.isBlank())
                ? new CommitLog(commitLogPath) : null;
    }

    /**
     * Register a listener notified after each {@code SUCCESS} batch is durably
     * committed (audit rows + commit-log line written). The {@code service} layer
     * passes a bus sink here so downstream stages (enrichment) can react. Set once
     * during setup; {@link #flush} reads it under the same lock.
     */
    public void setCommitListener(Consumer<BatchEvent> listener) {
        this.commitListener = listener;
    }

    /** One member-file audit row. */
    public record FileRow(String startTime, String endTime, String filename, String status,
                          long parsedRows, long errorRows, List<String> outputPaths,
                          List<Long> outputSizes, long durationMs, String error, String batchId) {}

    /** One batch-summary audit row. */
    public record BatchRow(String batchId, String pipeline, String schemaName, String outputTable,
                           String startTime, String endTime, String status,
                           int memberCount, int rejectedCount, long totalInputRows,
                           long totalOutputRows, int outputFileCount, long totalOutputBytes,
                           long durationMs, String error) {}

    /**
     * Append this batch's rows to the three audit CSVs, then record the batch in
     * the durable commit log (fsync'd). The commit-log write is last so a line
     * there means the audit rows are also written.
     */
    public synchronized void flush(BatchRow batch, List<FileRow> files, List<LineageRow> lineage) {
        appendStatus(files);
        appendBatch(batch);
        appendLineage(lineage);
        if (commitLog != null) {
            commitLog.record(batch.endTime(), batch.batchId(), batch.pipeline(), batch.status(),
                    batch.memberCount(), batch.outputFileCount(),
                    batch.totalOutputRows(), batch.totalOutputBytes());
        }
        // Emit a batch event for every terminal batch (SUCCESS + FAILED) so observability
        // sees error rates and latency; enrichment consumers filter on status. Fired last,
        // so a delivered event implies the audit + commit log are written.
        if (commitListener != null) {
            List<String> partitions = lineage.stream()
                    .map(LineageRow::partition).distinct().collect(Collectors.toList());
            commitListener.accept(new BatchEvent(
                    batch.pipeline(), batch.batchId(), batch.status(),
                    partitions, batch.totalOutputRows(), batch.durationMs(), batch.rejectedCount()));
        }
    }

    private void appendStatus(List<FileRow> files) {
        if (statusPath == null) return;
        boolean exists = new java.io.File(statusPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(statusPath, true))) {
            if (!exists)
                pw.println("start_time,end_time,filename,status,parsed_rows,error_rows," +
                        "output_paths,output_sizes_bytes,duration_ms,error,batch_id");
            for (FileRow f : files) {
                String paths = String.join(";", f.outputPaths()).replace('"', '\'');
                String sizes = f.outputSizes().stream().map(String::valueOf)
                        .collect(Collectors.joining(";"));
                pw.printf("%s,%s,%s,%s,%d,%d,\"%s\",\"%s\",%d,\"%s\",%s%n",
                        f.startTime(), f.endTime(), f.filename(), f.status(),
                        f.parsedRows(), f.errorRows(), paths, sizes, f.durationMs(),
                        f.error() == null ? "" : f.error().replace('"', '\''), f.batchId());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendBatch(BatchRow b) {
        if (batchesPath == null) return;
        boolean exists = new java.io.File(batchesPath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(batchesPath, true))) {
            if (!exists)
                pw.println("batch_id,pipeline,schema_name,output_table,start_time,end_time,status," +
                        "member_count,rejected_count,total_input_rows,total_output_rows," +
                        "output_file_count,total_output_bytes,duration_ms,error");
            pw.printf("%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,\"%s\"%n",
                    b.batchId(), b.pipeline(), b.schemaName(),
                    b.outputTable() == null ? "" : b.outputTable(),
                    b.startTime(), b.endTime(), b.status(),
                    b.memberCount(), b.rejectedCount(), b.totalInputRows(), b.totalOutputRows(),
                    b.outputFileCount(), b.totalOutputBytes(), b.durationMs(),
                    b.error() == null ? "" : b.error().replace('"', '\''));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void appendLineage(List<LineageRow> rows) {
        if (lineagePath == null) return;
        boolean exists = new java.io.File(lineagePath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(lineagePath, true))) {
            if (!exists)
                pw.println("batch_id,src_id,input_file,output_file,partition,row_count");
            for (LineageRow r : rows) {
                pw.printf("%s,%d,%s,\"%s\",%s,%d%n",
                        r.batchId(), r.srcId(), r.inputFile(),
                        r.outputFile().replace('"', '\''), r.partition(), r.rowCount());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
