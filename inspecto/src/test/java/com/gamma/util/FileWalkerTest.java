package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Phaser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileWalkerTest {

    private static Set<String> walk(Path root, java.util.function.Predicate<Path> enterDir) {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        Phaser ph = new Phaser(1);
        VirtualThreadRunner.submit(exec, ph, () -> FileWalker.walk(exec, ph, root, enterDir,
                f -> seen.add(f.getFileName().toString()),
                (d, e) -> seen.add("ERR:" + d.getFileName())));
        ph.arriveAndAwaitAdvance();
        exec.shutdown();
        return seen;
    }

    @Test
    void visitsEveryFileAcrossNestedDirectories(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("a.txt"));
        Files.createDirectories(dir.resolve("sub/deeper"));
        Files.createFile(dir.resolve("sub/b.txt"));
        Files.createFile(dir.resolve("sub/deeper/c.txt"));
        assertEquals(Set.of("a.txt", "b.txt", "c.txt"), walk(dir, null));
    }

    @Test
    void enterDirPredicatePrunesSubtrees(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("keep.txt"));
        Files.createDirectories(dir.resolve("skipme"));
        Files.createFile(dir.resolve("skipme/hidden.txt"));
        Set<String> seen = walk(dir, d -> !d.getFileName().toString().equals("skipme"));
        assertEquals(Set.of("keep.txt"), seen);
    }

    @Test
    void unreadableRootGoesToTheErrorHandlerNotAnException(@TempDir Path dir) {
        Set<String> seen = walk(dir.resolve("does-not-exist"), null);
        assertEquals(1, seen.size());
        assertTrue(seen.iterator().next().startsWith("ERR:"));
    }
}
