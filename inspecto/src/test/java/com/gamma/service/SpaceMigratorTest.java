package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The migrate command: a flat working-directory layout relocates into spaces/&lt;id&gt;/ that discovers cleanly. */
class SpaceMigratorTest {

    @Test
    void migratesAFlatTreeIntoADiscoverableSpace(@TempDir Path tmp) throws Exception {
        // ── a flat deployment: a config tree + the working-dir runtime artifacts ──
        Path workingDir = Files.createDirectories(tmp.resolve("flat"));
        Path flatConfig = Files.createDirectories(workingDir.resolve("config"));
        Path pipe = TestConfigs.csv(flatConfig, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(pipe, flatConfig.resolve("etl_pipeline.toon"));   // a *_pipeline.toon discover() will see
        Files.createDirectories(workingDir.resolve("database"));     // flat data
        Files.createDirectories(workingDir.resolve("jobs_audit"));   // flat audit
        Files.writeString(workingDir.resolve("inspecto-status.db"), "stub");   // a flat duckdb file

        Path spacesRoot = tmp.resolve("spaces");
        List<SpaceMigrator.Step> applied = SpaceMigrator.migrate(workingDir, flatConfig, spacesRoot, "default", false);
        assertFalse(applied.isEmpty(), "steps were applied");

        // ── the per-space layout is in place; the flat sources are gone ──
        Path base = spacesRoot.resolve("default");
        assertTrue(Files.exists(base.resolve("config").resolve("etl_pipeline.toon")), "config tree relocated");
        assertTrue(Files.isDirectory(base.resolve("data")), "database → data");
        assertTrue(Files.isDirectory(base.resolve("audit")), "jobs_audit → audit");
        assertTrue(Files.exists(base.resolve("duckdb").resolve("inspecto-status.db")), "db file → duckdb/");
        assertTrue(Files.exists(base.resolve("space.toon")), "manifest written");
        assertFalse(Files.exists(flatConfig), "the flat config was moved, not copied");
        assertFalse(Files.exists(workingDir.resolve("database")), "the flat data dir was moved");

        // ── it boots: discover finds exactly the one migrated space ──
        try (SpaceManager mgr = SpaceManager.discover(spacesRoot)) {
            assertEquals(1, mgr.size());
            assertTrue(mgr.space(SpaceId.of("default")).isPresent());
        }

        // ── idempotent: re-running relocates nothing (sources already gone) ──
        assertTrue(SpaceMigrator.migrate(workingDir, flatConfig, spacesRoot, "default", false).isEmpty(),
                "a second migrate is a clean no-op");
    }

    @Test
    void dryRunPlansButMovesNothing(@TempDir Path tmp) throws Exception {
        Path workingDir = Files.createDirectories(tmp.resolve("flat"));
        Path flatConfig = Files.createDirectories(workingDir.resolve("config"));
        Files.writeString(flatConfig.resolve("x_pipeline.toon"), "name: X\n");
        Files.createDirectories(workingDir.resolve("database"));

        Path spacesRoot = tmp.resolve("spaces");
        List<SpaceMigrator.Step> plan = SpaceMigrator.migrate(workingDir, flatConfig, spacesRoot, "default", true);

        assertFalse(plan.isEmpty(), "a dry run still reports the plan");
        assertFalse(Files.exists(spacesRoot.resolve("default")), "dry run created nothing");
        assertTrue(Files.exists(flatConfig), "dry run moved nothing");
    }
}
