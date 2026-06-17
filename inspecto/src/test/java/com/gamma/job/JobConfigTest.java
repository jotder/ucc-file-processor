package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for parsing a {@code *_job.toon} into a {@link JobConfig}. */
class JobConfigTest {

    private static Path write(Path dir, String name, String toon) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void parsesTypeCronAndParams(@TempDir Path dir) throws Exception {
        Path p = write(dir, "clean_job.toon", """
                job:
                  name: nightly-clean
                  type: maintenance
                  cron: "0 2 * * *"
                  on_pipeline: EVENTS
                  task: cleanup
                  dir: some/audit/dir
                  retention_days: 30
                """);
        JobConfig c = JobConfig.load(p.toString());
        assertEquals("nightly-clean", c.name());
        assertEquals(JobType.MAINTENANCE, c.type());
        assertEquals("0 2 * * *", c.cron());
        assertTrue(c.hasCron());
        assertTrue(c.hasEvent());
        assertEquals("EVENTS", c.onPipeline());
        assertTrue(c.enabled(), "enabled defaults to true");
        assertEquals("cleanup", c.require("task"));
        assertEquals("some/audit/dir", c.require("dir"));
        assertEquals("30", c.opt("retention_days", "7"));
        assertNotNull(c.cronExpression());
    }

    @Test
    void enabledFalseIsHonoured(@TempDir Path dir) throws Exception {
        Path p = write(dir, "off_job.toon", """
                job:
                  name: disabled-report
                  type: report
                  enabled: false
                  scope: status
                """);
        JobConfig c = JobConfig.load(p.toString());
        assertFalse(c.enabled());
        assertEquals(JobType.REPORT, c.type());
        assertFalse(c.hasCron());
        assertFalse(c.hasEvent());
    }

    @Test
    void rejectsUnknownTypeAndBadCron(@TempDir Path dir) throws Exception {
        Path badType = write(dir, "bt_job.toon", """
                job:
                  name: x
                  type: frobnicate
                """);
        assertThrows(IllegalArgumentException.class, () -> JobConfig.load(badType.toString()));

        Path badCron = write(dir, "bc_job.toon", """
                job:
                  name: y
                  type: enrich
                  cron: "not a cron"
                """);
        assertThrows(IllegalArgumentException.class, () -> JobConfig.load(badCron.toString()));
    }

    @Test
    void requiresAJobSection(@TempDir Path dir) throws Exception {
        Path p = write(dir, "empty_job.toon", "name: nope\n");
        assertThrows(IllegalArgumentException.class, () -> JobConfig.load(p.toString()));
    }
}
