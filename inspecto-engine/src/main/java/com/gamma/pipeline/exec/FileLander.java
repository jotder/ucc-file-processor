package com.gamma.pipeline.exec;

import com.gamma.api.PublicApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * <b>T13 — land-then-ack.</b> Durably lands an {@code adapter}'s micro-batch as a file in the staging /
 * inbox directory, then acknowledges the source — in that order, so a crash never acks data that isn't on
 * disk (§3.6). The sequence mirrors the acquisition ledger's fetch-then-commit ordering ⇒ at-least-once
 * (downstream fingerprint dedup if needed):
 * <ol>
 *   <li>write the bytes to a unique {@code .tmp} file and {@code fsync} (data durable);</li>
 *   <li><b>atomically rename</b> the temp file into the staging dir (the file "appears" all-at-once);</li>
 *   <li><b>only then</b> run the {@code ack} (commit the stream offset / ack the message).</li>
 * </ol>
 * A crash before step 2 leaves only an ignorable {@code .tmp}; a crash between 2 and 3 lands the file
 * without acking, so the source re-delivers and the file is re-landed (at-least-once).
 */
@PublicApi(since = "4.3.0")
public final class FileLander {

    private FileLander() {}

    /**
     * Land {@code bytes} as {@code fileName} under {@code stagingDir}, then run {@code ack}.
     *
     * @param stagingDir the inbox / staging directory the {@code acquisition} stage polls (created if absent)
     * @param fileName   the final file name (should be unique per micro-batch — e.g. a sequence/timestamp)
     * @param bytes      the micro-batch payload
     * @param ack        commit/ack callback, run <b>only after</b> the durable atomic rename ({@code null} = none)
     * @return the landed file path
     */
    public static Path land(Path stagingDir, String fileName, byte[] bytes, Runnable ack) throws IOException {
        Files.createDirectories(stagingDir);
        Path target = stagingDir.resolve(fileName);
        Path tmp = stagingDir.resolve(fileName + ".tmp-" + UUID.randomUUID());

        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(bytes));
            ch.force(true);   // fsync — the data is durable before it becomes visible
        }

        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);   // best-effort on FS without atomic rename
        }

        // Ack LAST — after the file is durably visible in the inbox (at-least-once on crash).
        if (ack != null) ack.run();
        return target;
    }
}
