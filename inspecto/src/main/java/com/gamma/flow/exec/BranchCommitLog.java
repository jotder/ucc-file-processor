package com.gamma.flow.exec;

import com.gamma.api.PublicApi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>T11 — partial-commit state with a branch dimension.</b> A durable, append-only,
 * {@code fsync}-per-record ledger (the same crash-safety contract as {@link com.gamma.etl.CommitLog})
 * extended with the {@code (batch_id, branch)} key a branch-aware flow needs. Two phases are recorded:
 * <ul>
 *   <li>{@code BRANCH} — one branch's outputs + manifest are durable on disk;</li>
 *   <li>{@code SOURCE} — every branch committed and the source files were finalised (backup / markers
 *       LAST / ledger / watermark).</li>
 * </ul>
 *
 * <p>This is what lets {@link BranchCommitCoordinator} resume a half-committed batch idempotently: on
 * replay an already-{@code BRANCH}-recorded branch is skipped, and source-finalisation runs only when
 * every expected branch is present and no {@code SOURCE} row exists yet. A single-branch flow degrades
 * to one {@code BRANCH} row + one {@code SOURCE} row — the legacy single-output sequence.
 *
 * <p>Format (CSV, header on first creation): {@code recorded_at,batch_id,branch,phase} (a {@code SOURCE}
 * row uses {@code *} for the branch).
 */
@PublicApi(since = "4.3.0")
public final class BranchCommitLog {

    private static final String HEADER = "recorded_at,batch_id,branch,phase\n";
    static final String PHASE_BRANCH = "BRANCH";
    static final String PHASE_SOURCE = "SOURCE";

    private final Path file;

    public BranchCommitLog(String path) {
        this.file = Paths.get(path);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            if (!Files.exists(file)) appendAndSync(HEADER);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot initialise branch commit log: " + path, e);
        }
    }

    /** Record that {@code branch} of {@code batchId} is durably committed (outputs + manifest). */
    public synchronized void recordBranch(String batchId, String branch) {
        append(batchId, branch, PHASE_BRANCH);
    }

    /** Record that {@code batchId}'s source files were finalised (all branches done). */
    public synchronized void recordSourceFinalized(String batchId) {
        append(batchId, "*", PHASE_SOURCE);
    }

    /** Branches recorded {@code BRANCH}-committed for {@code batchId} (empty if none / no log yet). */
    public Set<String> committedBranches(String batchId) {
        Set<String> out = new HashSet<>();
        forEachRow((bId, branch, phase) -> {
            if (PHASE_BRANCH.equals(phase) && bId.equals(batchId)) out.add(branch);
        });
        return out;
    }

    /** Whether {@code batchId} has a durable {@code SOURCE} (source-finalised) record. */
    public boolean isSourceFinalized(String batchId) {
        boolean[] seen = {false};
        forEachRow((bId, branch, phase) -> {
            if (PHASE_SOURCE.equals(phase) && bId.equals(batchId)) seen[0] = true;
        });
        return seen[0];
    }

    public Path path() { return file; }

    // ── internals ──────────────────────────────────────────────────────────────

    private void append(String batchId, String branch, String phase) {
        String line = String.join(",", Instant.now().toString(), batchId, branch, phase) + "\n";
        try {
            appendAndSync(line);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to branch commit log: " + file, e);
        }
    }

    private interface RowConsumer { void accept(String batchId, String branch, String phase); }

    private void forEachRow(RowConsumer c) {
        if (!Files.exists(file)) return;
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isBlank() || l.startsWith("recorded_at,")) continue;
                String[] f = l.split(",", -1);
                if (f.length >= 4) c.accept(f[1], f[2], f[3]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read branch commit log: " + file, e);
        }
    }

    /** Append bytes and force them (data + metadata) to disk before returning — the durability point. */
    private void appendAndSync(String text) throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
    }
}
