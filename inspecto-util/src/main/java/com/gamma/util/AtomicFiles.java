package com.gamma.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file writes. Stages the bytes in a sibling temp file, then renames it over the
 * target so a partial or concurrent reader never observes a half-written file.
 *
 * <p>The rename uses {@link StandardCopyOption#ATOMIC_MOVE} where the filesystem supports it,
 * falling back to a plain replacing move otherwise. Consolidated from five byte-identical inline
 * copies (the {@code config} and {@code connection} writes in the control API, plus the flow,
 * component and view stores).
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Atomically create-or-replace {@code target} with {@code bytes}. The parent directory is
     * created if missing; the staging temp file is created in the target's directory with
     * {@code tempPrefix} and removed even if the move fails.
     *
     * @param target     the file to create or replace
     * @param bytes      the full file contents
     * @param tempPrefix prefix for the staging temp file (e.g. {@code ".cfg-"})
     */
    public static void write(Path target, byte[] bytes, String tempPrefix) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), tempPrefix, ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
