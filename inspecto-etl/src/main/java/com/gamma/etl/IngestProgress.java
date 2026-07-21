package com.gamma.etl;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Live per-file ingest progress, per pipeline (file-processing visibility).
 *
 * <p>The batch strategies report the member they are about to ingest ({@link #track}); the
 * Control API's inbox status reads it back ({@link #current}) so an operator can see
 * <em>which</em> file a mid-ingest pipeline is working through ("file 3 of 120: x.csv.gz") —
 * previously "Processing" was only a pipeline-level yes/no. {@link BatchProcessor} clears the
 * entry when the batch finishes, so a snapshot is only ever visible while ingest is live.
 *
 * <p>Deliberately a process-local, in-memory map (not durable): ingest is synchronous within a
 * poll cycle and the signal is purely observational — after a crash there is no in-flight file,
 * so there is nothing worth persisting. Writes are two volatile map ops per member, so the
 * hot ingest loop pays effectively nothing.
 */
public final class IngestProgress {

    /** What a pipeline is ingesting right now: file {@code index} of {@code total} in a batch. */
    public record Snapshot(String batchId, String file, int index, int total, String startedAt) {}

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ConcurrentMap<String, Snapshot> CURRENT = new ConcurrentHashMap<>();

    private IngestProgress() {}

    /** Record that {@code pipeline} is now ingesting {@code file} ({@code index} of {@code total}, 1-based). */
    public static void track(String pipeline, String batchId, String file, int index, int total) {
        if (pipeline == null || pipeline.isBlank()) return;
        CURRENT.put(pipeline, new Snapshot(batchId, file, index, total,
                LocalDateTime.now().format(TS)));
    }

    /** Drop the pipeline's snapshot (batch finished — success, empty, or failed alike). */
    public static void clear(String pipeline) {
        if (pipeline != null) CURRENT.remove(pipeline);
    }

    /** The file the pipeline is ingesting right now, or {@code null} when not mid-file. */
    public static Snapshot current(String pipeline) {
        return pipeline == null ? null : CURRENT.get(pipeline);
    }
}
