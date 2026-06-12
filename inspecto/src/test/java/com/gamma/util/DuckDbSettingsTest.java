package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the 3.10.0 scratch-relocation primitives: the temp DB is created in the requested
 * directory (the data volume, not {@code java.io.tmpdir}), and the optional DuckDB resource
 * controls are actually applied to the connection.
 */
class DuckDbSettingsTest {

    @Test
    void tempDbFileIsCreatedInTheGivenDirectory(@TempDir Path dir) throws Exception {
        Path scratch = dir.resolve("scratch");
        File db = DuckDbUtil.tempDbFile("duckdb_test_", scratch);
        // The file is pre-deleted (DuckDB creates it on connect), but its PARENT is the scratch dir
        // we asked for — proving scratch no longer defaults to java.io.tmpdir.
        assertEquals(scratch.toAbsolutePath().normalize(), db.getParentFile().toPath().toAbsolutePath().normalize());
        assertTrue(Files.isDirectory(scratch), "scratch dir is created if absent");
        String sysTmp = System.getProperty("java.io.tmpdir");
        assertFalse(db.getParentFile().toPath().toAbsolutePath().normalize()
                        .equals(Path.of(sysTmp).toAbsolutePath().normalize()),
                "temp DB must not land in the system temp dir");
    }

    @Test
    void applyDuckDbSettingsSetsMemoryAndTempDirectory(@TempDir Path dir) throws Exception {
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("duckdb_test_", dir);
        Path spill = dir.resolve("spill");
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            String defaultMem = currentSetting(conn, "memory_limit");   // ~80% RAM (GiB)
            DuckDbUtil.applyDuckDbSettings(conn, "512MB",
                    spill.toAbsolutePath().toString(), "1GB");
            String setMem = currentSetting(conn, "memory_limit");
            // Echo format/units are version-dependent (e.g. "488.2 MiB" for 512 decimal-MB); just
            // prove the SET took effect — the cap changed from the default and is now in MiB range.
            assertNotEquals(defaultMem, setMem, "memory_limit should change after applyDuckDbSettings");
            assertTrue(setMem.contains("MiB"), "a ~512MB cap should report in MiB, got " + setMem);
            assertTrue(currentSetting(conn, "temp_directory").replace('\\', '/').endsWith("spill"),
                    "temp_directory should point at the configured spill dir");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    @Test
    void applyDuckDbSettingsIsNoOpWhenAllNull(@TempDir Path dir) throws Exception {
        DuckDbUtil.loadDriver();
        File db = DuckDbUtil.tempDbFile("duckdb_test_", dir);
        try (Connection conn = DuckDbUtil.openConnection(db)) {
            // Must not throw and must leave DuckDB's own defaults in place.
            assertDoesNotThrow(() -> DuckDbUtil.applyDuckDbSettings(conn, null, null, null));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── effectiveWorkerThreads: the anti-oversubscription policy ─────────────────────────────

    @Test
    void explicitPositiveDuckDbThreadsIsHonoredVerbatim() {
        // A user who tuned the value gets exactly that, regardless of cores/concurrency.
        assertEquals(4, DuckDbUtil.effectiveWorkerThreads(4, 8, 56));
        assertEquals(1, DuckDbUtil.effectiveWorkerThreads(1, 1, 56));
        assertEquals(64, DuckDbUtil.effectiveWorkerThreads(64, 16, 56)); // even over-cores, honored
    }

    @Test
    void defaultZeroAutoDividesCoresAmongConcurrentBatches() {
        // The pathology fix: 16 batches on 56 cores → 3 threads each (≈ cores), not 56 each.
        assertEquals(3, DuckDbUtil.effectiveWorkerThreads(0, 16, 56));
        assertEquals(7, DuckDbUtil.effectiveWorkerThreads(0, 8, 56));
        // Floors at 1 when batches outnumber cores (never PRAGMA threads=0 by accident).
        assertEquals(1, DuckDbUtil.effectiveWorkerThreads(0, 64, 56));
    }

    @Test
    void singleBatchKeepsAllCores() {
        // One batch can't oversubscribe — return 0 (the "leave DuckDB default = all cores" sentinel).
        assertEquals(0, DuckDbUtil.effectiveWorkerThreads(0, 1, 56));
        assertEquals(0, DuckDbUtil.effectiveWorkerThreads(0, 0, 56));
    }

    @Test
    void negativeDuckDbThreadsOptsOutToDuckDbDefault() {
        // -1 = "I really want every batch to use all cores" → 0 sentinel, even under concurrency.
        assertEquals(0, DuckDbUtil.effectiveWorkerThreads(-1, 16, 56));
        assertEquals(0, DuckDbUtil.effectiveWorkerThreads(-1, 1, 8));
    }

    private static String currentSetting(Connection conn, String key) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_setting('" + key + "')")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
