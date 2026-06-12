package com.gamma.etl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Durable, append-only ledger of committed batches — the single source of truth
 * for "did this batch finish".
 *
 * <p>The per-run {@code _batches_<ts>.csv} audit already lists one row per batch,
 * but it is (a) buffered, so a crash can lose the tail, and (b) timestamped per
 * run, so there is no one file to consult across runs. The commit log fixes both:
 * it is a single persistent file (no run timestamp) and every record is
 * {@code fsync}'d to disk before {@link #record} returns. A line therefore means
 * the batch's outputs, manifest, backup, and markers are all durably on disk —
 * it is written as the final step of a committed batch.
 *
 * <p>Format (CSV, header on first creation):
 * <pre>committed_at,batch_id,pipeline,status,member_count,output_count,output_rows,output_bytes</pre>
 *
 * <p>{@link #record} is {@code synchronized}; concurrent batches in one run share
 * a single instance. Concurrent runs of the <em>same</em> pipeline (which would
 * also collide on the run-timestamped audit files) are an operator error and not
 * guarded here beyond the OS append semantics.
 */
public final class CommitLog {

    private static final String HEADER =
            "committed_at,batch_id,pipeline,status,member_count,output_count,output_rows,output_bytes\n";

    private final Path file;

    /**
     * Open (creating if needed) the commit log at {@code path}. Writes the CSV
     * header on first creation. {@code path} is typically
     * {@code cfg.dirs().commitLogPath()}.
     */
    public CommitLog(String path) {
        this.file = Paths.get(path);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (!Files.exists(file)) appendAndSync(HEADER);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialise commit log: " + path, e);
        }
    }

    /**
     * Append one committed-batch record and {@code fsync}. The CSV columns mirror
     * {@link #HEADER}. Thread-safe.
     */
    public synchronized void record(String committedAt, String batchId, String pipeline,
                                    String status, int memberCount, int outputCount,
                                    long outputRows, long outputBytes) {
        String line = String.join(",",
                committedAt, batchId, pipeline, status,
                Integer.toString(memberCount), Integer.toString(outputCount),
                Long.toString(outputRows), Long.toString(outputBytes)) + "\n";
        try {
            appendAndSync(line);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to commit log: " + file, e);
        }
    }

    /**
     * Read back the set of batch ids recorded with {@code status == "SUCCESS"}.
     * Useful for crash recovery / "what already finished" queries. Returns an
     * empty set if the log does not exist yet.
     */
    public Set<String> committedBatchIds() {
        Set<String> ids = new HashSet<>();
        if (!Files.exists(file)) return ids;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String l : lines) {
                if (l.isBlank() || l.startsWith("committed_at,")) continue;
                String[] c = l.split(",", -1);
                if (c.length >= 4 && "SUCCESS".equals(c[3])) ids.add(c[1]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read commit log: " + file, e);
        }
        return ids;
    }

    /** Path to the underlying log file. */
    public Path path() { return file; }

    // ── internals ──────────────────────────────────────────────────────────────

    /** Append bytes and force them (data + metadata) to disk before returning. */
    private void appendAndSync(String text) throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);   // fsync — the durability point
        }
    }
}
