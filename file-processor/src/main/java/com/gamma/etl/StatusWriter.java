package com.gamma.etl;

import com.gamma.util.DuckDbUtil;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Appends one audit row per processed file to the run-scoped status CSV.
 *
 * <p>The file is created with a header on first write and appended on every
 * subsequent call within the same run.  Because multiple worker threads call
 * this concurrently the method is {@code synchronized} on the class object.
 *
 * <p>Columns: {@code start_time, end_time, filename, status, parsed_rows,
 * error_rows, output_paths, output_sizes_bytes, duration_ms, error}
 *
 * <p>Multiple output paths / sizes are joined with {@code ;} and quoted so the
 * CSV remains parseable.
 *
 * <p>Extracted from {@link com.gamma.inspector.SourceProcessor#updateStatus}.
 */
public final class StatusWriter {

    private StatusWriter() {}

    /**
     * Append one audit row to the pipeline's status CSV (thread-safe).
     *
     * <p>No-op when {@link PipelineConfig#statusFilePath} is {@code null} or blank.
     *
     * @param fileName  source filename
     * @param status    e.g. {@code "SUCCESS"}, {@code "QUARANTINED_MISMATCH"}, {@code "FAILED"}
     * @param ingest    ingestion counts for this file
     * @param transform output paths and sizes (use {@link TransformResult#empty()} if unavailable)
     * @param startTime processing start time
     * @param endTime   processing end time
     * @param durationMs elapsed milliseconds
     * @param error     error message (empty string on success)
     * @param cfg       pipeline configuration providing the status file path
     */
    public static synchronized void append(
            String fileName, String status,
            IngestResult ingest, TransformResult transform,
            LocalDateTime startTime, LocalDateTime endTime,
            long durationMs, String error,
            PipelineConfig cfg) {

        if (cfg.statusFilePath == null || cfg.statusFilePath.isBlank()) return;

        boolean exists = new java.io.File(cfg.statusFilePath).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(cfg.statusFilePath, true))) {
            if (!exists)
                pw.println("start_time,end_time,filename,status," +
                        "parsed_rows,error_rows,output_paths,output_sizes_bytes,duration_ms,error");

            String paths = String.join(";", transform.outputPaths()).replace('"', '\'');
            String sizes = transform.outputSizes().stream()
                    .map(String::valueOf).collect(Collectors.joining(";"));

            pw.printf("%s,%s,%s,%s,%d,%d,\"%s\",\"%s\",%d,\"%s\"%n",
                    startTime.format(DuckDbUtil.DT_FMT),
                    endTime.format(DuckDbUtil.DT_FMT),
                    fileName,
                    status,
                    ingest.parsedRows(),
                    ingest.errorRows(),
                    paths,
                    sizes,
                    durationMs,
                    error == null ? "" : error.replace('"', '\''));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
