package com.gamma.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The one parallel recursive directory walk — behavior injected, mechanics shared.
 * Replaces the {@code walkParallel} recursion previously copy-pasted (with small
 * variations) across the pre-ETL utilities: each subdirectory is resubmitted as its own
 * virtual-thread task (via {@link VirtualThreadRunner}), files are handed to the injected
 * consumer, and per-directory I/O errors go to the injected handler — a failed directory
 * never aborts the walk.
 *
 * <p>Callers own the executor + phaser (they typically fan out further work onto the same
 * pair from inside {@code onFile}) and await completion with
 * {@code phaser.arriveAndAwaitAdvance()} as before.
 */
public final class FileWalker {

    private FileWalker() {}

    /** Walk with no directory filter — every subdirectory is entered. */
    public static void walk(ExecutorService exec, Phaser ph, Path root,
                            Consumer<Path> onFile, BiConsumer<Path, IOException> onError) {
        walk(exec, ph, root, null, onFile, onError);
    }

    /**
     * Walk {@code root} recursively in parallel.
     *
     * @param enterDir invoked once per directory (including {@code root}) before it is
     *                 scanned; return {@code false} to prune that subtree. Also the hook
     *                 for per-directory side effects (progress logging). {@code null} = enter all.
     * @param onFile   invoked for every non-directory entry, on the walking thread; hand
     *                 heavy work back to the executor via {@link VirtualThreadRunner#submit}
     * @param onError  invoked with the directory that failed to list and the error
     */
    public static void walk(ExecutorService exec, Phaser ph, Path root,
                            Predicate<Path> enterDir, Consumer<Path> onFile,
                            BiConsumer<Path, IOException> onError) {
        if (enterDir != null && !enterDir.test(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    VirtualThreadRunner.submit(exec, ph,
                            () -> walk(exec, ph, entry, enterDir, onFile, onError));
                } else {
                    onFile.accept(entry);
                }
            }
        } catch (IOException e) {
            onError.accept(root, e);
        }
    }
}
