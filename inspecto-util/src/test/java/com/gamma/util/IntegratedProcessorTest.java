package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link IntegratedProcessor}: extract 'unknown' archives, move dated members, sentinel idempotency. */
class IntegratedProcessorTest {

    private static final String MEMBER = "cbs_cdr_adj_20260102_part1.add.gz";

    @Test
    void extractsUnknownArchivesAndMovesDatedMembers(@TempDir Path root, @TempDir Path temp,
                                                     @TempDir Path target) throws Exception {
        TarFixtures.writeTar(root.resolve("unknown").resolve("delivery.tar.gz"),
                Map.of(MEMBER, "payload", "ignored_readme.txt", "no date pattern"));
        // An archive NOT under an 'unknown' dir must be left alone.
        TarFixtures.writeTar(root.resolve("known").resolve("other.tar.gz"), Map.of(MEMBER, "x"));

        new IntegratedProcessor(root.toString(), temp.toString(), target.toString(), false).run();

        Path moved = target.resolve("20260102").resolve(MEMBER);
        assertTrue(Files.exists(moved), "dated member moved to target/<date>/");
        assertEquals("payload", Files.readString(moved));
        assertTrue(Files.exists(temp.resolve("delivery").resolve(TarUtil.SENTINEL)), "sentinel written");
        assertTrue(Files.exists(temp.resolve("delivery").resolve("ignored_readme.txt")),
                "non-matching member extracted but not moved");
        assertFalse(Files.exists(target.resolve("20260102").resolve("other.tar.gz")));

        // Rerun: the sentinel short-circuits re-extraction and the moved file is untouched.
        new IntegratedProcessor(root.toString(), temp.toString(), target.toString(), false).run();
        assertEquals("payload", Files.readString(moved), "idempotent on rerun");
    }

    @Test
    void dryRunExtractsAndMovesNothing(@TempDir Path root, @TempDir Path temp,
                                       @TempDir Path target) throws Exception {
        TarFixtures.writeTar(root.resolve("unknown").resolve("delivery.tar.gz"), Map.of(MEMBER, "payload"));

        new IntegratedProcessor(root.toString(), temp.toString(), target.toString(), true).run();

        assertFalse(Files.exists(temp.resolve("delivery")), "nothing extracted in dry-run");
        assertFalse(Files.exists(target.resolve("20260102")), "nothing moved in dry-run");
    }

    @Test
    void missingWalkRootFailsFast(@TempDir Path dir) throws Exception {
        IntegratedProcessor p = new IntegratedProcessor(
                dir.resolve("ghost").toString(), dir.resolve("t").toString(), dir.resolve("o").toString(), true);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, p::run);
        assertTrue(e.getMessage().contains("walk root does not exist"));
    }
}
