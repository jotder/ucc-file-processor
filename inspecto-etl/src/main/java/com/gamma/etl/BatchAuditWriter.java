package com.gamma.etl;

import com.gamma.util.CsvLedger;

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
 * <p>Each CSV is a {@link CsvLedger} whose codec preserves this writer's exact column
 * order and quoting. {@link #flush} is {@code synchronized} so each batch's rows are
 * written contiguously even when multiple batches finish concurrently.
 */
public final class BatchAuditWriter {

    private final CsvLedger<FileRow> status;     // null when no status path configured
    private final CsvLedger<BatchRow> batches;   // null when no batches path configured
    private final CsvLedger<LineageRow> lineage; // null when no lineage path configured
    private final CommitLog commitLog;           // null when no commit-log path is configured
    private Consumer<BatchEvent> commitListener; // null = no event emission
    private Consumer<BatchEvent> terminalBatchSink; // null = no ledger Signal emission

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
        this.status = statusPath == null ? null : new CsvLedger<>(statusPath,
                "start_time,end_time,filename,status,parsed_rows,error_rows," +
                        "output_paths,output_sizes_bytes,duration_ms,error,batch_id",
                BatchAuditWriter::statusLine);
        this.batches = batchesPath == null ? null : new CsvLedger<>(batchesPath,
                "batch_id,pipeline,schema_name,output_table,start_time,end_time,status," +
                        "member_count,rejected_count,total_input_rows,total_output_rows," +
                        "output_file_count,total_output_bytes,duration_ms,error",
                BatchAuditWriter::batchLine);
        this.lineage = lineagePath == null ? null : new CsvLedger<>(lineagePath,
                "batch_id,src_id,input_file,output_file,partition,row_count",
                BatchAuditWriter::lineageLine);
        this.commitLog = (commitLogPath != null && !commitLogPath.isBlank())
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

    /**
     * Register a sink for the canonical {@code pipeline.batch.committed|failed} Signal derived from
     * each terminal batch. The composition root wires this to {@code PipelineBatchSignal::emit}; a
     * {@code null} sink emits nothing. Keeping the sink here (rather than building the Signal inline)
     * keeps {@code com.gamma.etl} free of the {@code event}/{@code signal} packages — the Signal
     * construction + ledger emission live above etl, so etl stays a foundation layer.
     */
    public void setTerminalBatchSink(Consumer<BatchEvent> sink) {
        this.terminalBatchSink = sink;
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
    public synchronized void flush(BatchRow batch, List<FileRow> files, List<LineageRow> lineageRows) {
        if (status  != null) status.appendAll(files);
        if (batches != null) batches.append(batch);
        if (lineage != null) lineage.appendAll(lineageRows);
        if (commitLog != null) {
            commitLog.record(batch.endTime(), batch.batchId(), batch.pipeline(), batch.status(),
                    batch.memberCount(), batch.outputFileCount(),
                    batch.totalOutputRows(), batch.totalOutputBytes());
        }
        // Build the terminal-batch event once (every flush is a terminal batch: SUCCESS + FAILED) and
        // fan it out to both observers so observability sees error rates and latency; enrichment
        // consumers filter on status. Fired last, so a delivered event implies the audit + commit log
        // are written. The error detail (error/offendingFile/errorRows) lets the assist agent's failure
        // reactor (M7) diagnose a FAILED batch; it is operational metadata derived from the audit rows,
        // never row content.
        //  - commitListener   : the service's BatchEventBus sink (enrichment triggers, ...).
        //  - terminalBatchSink : emits the canonical pipeline.batch.committed|failed Signal onto the
        //    ledger, wired by the composition root to PipelineBatchSignal::emit. Keeping the Signal
        //    construction above etl is what lets etl stay a foundation layer (no event/signal imports).
        if (commitListener != null || terminalBatchSink != null) {
            List<String> partitions = lineageRows.stream()
                    .map(LineageRow::partition).distinct().collect(Collectors.toList());
            String offendingFile = files.stream()
                    .filter(f -> f.error() != null && !f.error().isBlank())
                    .map(FileRow::filename).findFirst().orElse(null);
            long errorRows = files.stream().mapToLong(FileRow::errorRows).sum();
            BatchEvent event = new BatchEvent(
                    batch.pipeline(), batch.batchId(), batch.status(),
                    partitions, batch.totalOutputRows(), batch.durationMs(), batch.rejectedCount(),
                    batch.error(), offendingFile, errorRows);
            if (commitListener != null) commitListener.accept(event);
            if (terminalBatchSink != null) terminalBatchSink.accept(event);
        }
    }

    // ── row codecs (column order + quoting identical to the pre-CsvLedger writers) ──

    private static String statusLine(FileRow f) {
        String paths = String.join(";", f.outputPaths()).replace('"', '\'');
        String sizes = f.outputSizes().stream().map(String::valueOf)
                .collect(Collectors.joining(";"));
        return String.format("%s,%s,%s,%s,%d,%d,\"%s\",\"%s\",%d,\"%s\",%s",
                f.startTime(), f.endTime(), f.filename(), f.status(),
                f.parsedRows(), f.errorRows(), paths, sizes, f.durationMs(),
                CsvLedger.q(f.error()), f.batchId());
    }

    private static String batchLine(BatchRow b) {
        return String.format("%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,\"%s\"",
                b.batchId(), b.pipeline(), b.schemaName(),
                b.outputTable() == null ? "" : b.outputTable(),
                b.startTime(), b.endTime(), b.status(),
                b.memberCount(), b.rejectedCount(), b.totalInputRows(), b.totalOutputRows(),
                b.outputFileCount(), b.totalOutputBytes(), b.durationMs(),
                CsvLedger.q(b.error()));
    }

    private static String lineageLine(LineageRow r) {
        return String.format("%s,%d,%s,\"%s\",%s,%d",
                r.batchId(), r.srcId(), r.inputFile(),
                CsvLedger.q(r.outputFile()), r.partition(), r.rowCount());
    }
}
