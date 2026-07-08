package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The boot-time storage-layout contract: WARN-only findings, never a boot failure. */
class SpaceLayoutContractTest {

    /** Mint a conforming space tree under {@code base}. */
    private static Path conformingSpace(Path base) throws Exception {
        Files.createDirectories(base.resolve("config"));
        Files.createDirectories(base.resolve("data"));
        Files.createDirectories(base.resolve("audit"));
        Files.createDirectories(base.resolve("duckdb"));
        return base;
    }

    @Test
    void conformingTreeHasNoViolations(@TempDir Path tmp) throws Exception {
        Path base = conformingSpace(tmp.resolve("finance"));
        Files.writeString(base.resolve("space.toon"), "display_name: Finance\n");
        Files.createDirectories(base.resolve("flows"));   // historical, allowed
        Files.createDirectories(base.resolve("views"));   // T32, allowed
        Files.writeString(base.resolve("duckdb").resolve("inspecto-status.db"), "stub");   // db file in its home

        assertTrue(SpaceLayoutContract.verify(SpaceRoot.under(base)).isEmpty(),
                "a conforming tree yields no findings");
    }

    @Test
    void flagsMissingCanonicalSubdir(@TempDir Path tmp) throws Exception {
        Path base = tmp.resolve("audit-min");
        Files.createDirectories(base.resolve("config"));
        Files.createDirectories(base.resolve("data"));
        // no audit/ or duckdb/

        List<SpaceLayoutContract.Violation> found = SpaceLayoutContract.verify(SpaceRoot.under(base));
        assertEquals(2, found.stream().filter(v -> v.kind().equals("missing-subdir")).count());
    }

    @Test
    void flagsDbFileOutsideDuckdb(@TempDir Path tmp) throws Exception {
        Path base = conformingSpace(tmp.resolve("mixed"));
        Files.writeString(base.resolve("config").resolve("stray.duckdb"), "oops");   // wrong axis

        List<SpaceLayoutContract.Violation> found = SpaceLayoutContract.verify(SpaceRoot.under(base));
        assertTrue(found.stream().anyMatch(v -> v.kind().equals("db-file-outside-duckdb")),
                "a .duckdb file under config/ breaks the axis rule");
    }

    @Test
    void flagsUnexpectedTopLevelEntry(@TempDir Path tmp) throws Exception {
        Path base = conformingSpace(tmp.resolve("junky"));
        Files.createDirectories(base.resolve("scratch"));   // not part of the contract

        List<SpaceLayoutContract.Violation> found = SpaceLayoutContract.verify(SpaceRoot.under(base));
        assertTrue(found.stream().anyMatch(v -> v.kind().equals("unexpected-entry")
                        && v.path().getFileName().toString().equals("scratch")),
                "an unknown top-level dir is flagged");
    }

    @Test
    void legacyRootIsSkipped() {
        assertTrue(SpaceLayoutContract.verify(SpaceRoot.legacy()).isEmpty(),
                "the flat single-tenant layout has no contract tree to check");
    }
}
