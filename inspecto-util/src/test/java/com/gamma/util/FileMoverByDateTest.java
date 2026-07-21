package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** {@link FileMoverByDate}: date-pattern moves, dry-run, duplicate skip, source validation. */
class FileMoverByDateTest {

    private static final String MATCHING = "cbs_cdr_adj_20260101_part1.add.gz";

    @Test
    void movesMatchingFilesIntoDateDirsAndLeavesTheRest(@TempDir Path src, @TempDir Path base) throws Exception {
        Files.writeString(src.resolve(MATCHING), "data");
        Files.writeString(src.resolve("unrelated.txt"), "keep me");

        new FileMoverByDate(src.toString(), base.toString(), false).run();

        assertTrue(Files.exists(base.resolve("20260101").resolve(MATCHING)), "moved into the date dir");
        assertFalse(Files.exists(src.resolve(MATCHING)), "moved out of the source");
        assertTrue(Files.exists(src.resolve("unrelated.txt")), "non-matching file untouched");
    }

    @Test
    void dryRunMovesNothing(@TempDir Path src, @TempDir Path base) throws Exception {
        Files.writeString(src.resolve(MATCHING), "data");

        new FileMoverByDate(src.toString(), base.toString(), true).run();

        assertTrue(Files.exists(src.resolve(MATCHING)), "source untouched in dry-run");
        assertFalse(Files.exists(base.resolve("20260101").resolve(MATCHING)));
    }

    @Test
    void existingTargetIsSkippedNotOverwritten(@TempDir Path src, @TempDir Path base) throws Exception {
        Files.writeString(src.resolve(MATCHING), "new");
        Path target = base.resolve("20260101").resolve(MATCHING);
        Files.createDirectories(target.getParent());
        Files.writeString(target, "original");

        new FileMoverByDate(src.toString(), base.toString(), false).run();

        assertEquals("original", Files.readString(target), "existing target preserved");
        assertTrue(Files.exists(src.resolve(MATCHING)), "skipped source file stays put");
    }

    @Test
    void missingSourceDirFailsFast(@TempDir Path base) {
        FileMoverByDate mover = new FileMoverByDate(
                base.resolve("no-such-dir").toString(), base.toString(), false);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, mover::run);
        assertTrue(e.getMessage().contains("source directory does not exist"));
    }
}
