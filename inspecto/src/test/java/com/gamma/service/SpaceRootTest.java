package com.gamma.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SpaceRoot#under} per-space DB URLs: asking for any DuckDB URL mints {@code <base>/duckdb/} first.
 * Repo-checked-out spaces gitignore {@code duckdb/}, and DuckDB does not create parent dirs — without the
 * mkdir, every DB-backed store would silently degrade to in-memory on a fresh checkout.
 */
class SpaceRootTest {

    @Test
    void duckdbUrlsMintTheDuckdbDirSoFreshCheckoutsGetDurableStores(@TempDir Path tmp) {
        Path base = tmp.resolve("demo");   // no dirs exist yet — a fresh checkout's gitignored state
        SpaceRoot root = SpaceRoot.under(base);

        String url = root.objectsDbUrl();
        assertTrue(url.startsWith("jdbc:duckdb:"), url);
        assertTrue(Files.isDirectory(base.resolve("duckdb")), "duckdb/ is minted on first URL build");
        assertTrue(url.replace('\\', '/').contains("/duckdb/inspecto-ops.db"), url);

        // every store URL shares the same minted dir
        root.jobRunDbUrl();
        root.provenanceDbUrl();
        root.statusDbUrl();
        assertTrue(Files.isDirectory(base.resolve("duckdb")));
    }
}
