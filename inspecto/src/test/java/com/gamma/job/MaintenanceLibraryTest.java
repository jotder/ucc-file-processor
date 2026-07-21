package com.gamma.job;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The PIP-7 maintenance-task library: ledger_prune, db_maintenance, and Parquet partition compaction. */
class MaintenanceLibraryTest {

    private static JobConfig job(Map<String, String> params) {
        return new JobConfig("m", JobType.MAINTENANCE, null, null, true, false, params);
    }

    /** A real {@link RunContext} marked as a preview fire (MNT-1), audit files under {@code auditDir}. */
    private static JobContext dryCtx(Path auditDir) {
        RunContext ctx = new RunContext("r-dry", "default", "m", "manual", "r-dry", 0, Map.of(),
                new RunLogStore(auditDir.toString()), 100, new RunArtifactStore(auditDir.toString()));
        ctx.dryRun(true);
        return ctx;
    }

    @AfterEach
    void resetLedger() {
        AcquisitionLedgers.use(null);
    }

    // ── dry run (System Maintenance MNT-1) ───────────────────────────────────────

    @Test
    void cleanupDryRunReportsImpactButDeletesNothing(@TempDir Path junk, @TempDir Path audit) throws Exception {
        Path stale = junk.resolve("stale.csv");
        Files.writeString(stale, "old-data");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(30))));
        Path fresh = Files.writeString(junk.resolve("fresh.csv"), "new");
        JobConfig cfg = job(Map.of("task", "cleanup", "dir", junk.toString(), "retention_days", "7"));

        JobResult dry = new MaintenanceJob(cfg).run(dryCtx(audit));
        assertTrue(dry.message().contains("would delete 1 file(s), 8 byte(s)"), dry.message());
        assertTrue(Files.exists(stale), "dry run must not delete");
        assertTrue(Files.exists(fresh));

        // The real run matches the dry-run estimate.
        JobResult real = new MaintenanceJob(cfg).run();
        assertTrue(real.message().contains("deleted 1 file(s), 8 byte(s)"), real.message());
        assertFalse(Files.exists(stale), "real run deletes the stale file");
        assertTrue(Files.exists(fresh), "fresh file inside retention survives both runs");
    }

    @Test
    void ledgerPruneDryRunCountsWithoutForgetting(@TempDir Path audit) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        long old = System.currentTimeMillis() - Duration.ofDays(120).toMillis();
        ledger.record(new LedgerEntry("S", "old.csv", "old.csv", 1, null, null, null, old, old, LedgerEntry.PROCESSED));
        ledger.record(new LedgerEntry("S", "new.csv", "new.csv", 1, null, null, null, old,
                System.currentTimeMillis(), LedgerEntry.PROCESSED));

        JobResult r = new MaintenanceJob(job(Map.of("task", "ledger_prune",
                "retention_days", "90", "source", "S"))).run(dryCtx(audit));

        assertTrue(r.message().contains("would remove 1 fingerprint(s)"), r.message());
        assertTrue(ledger.find("S", "old.csv").isPresent(), "dry run must not prune");
        assertTrue(ledger.find("S", "new.csv").isPresent());
    }

    @Test
    void dryRunOnATaskWithoutPreviewDoesNothing(@TempDir Path root, @TempDir Path audit) throws Exception {
        Files.writeString(root.resolve("keep.parquet"), "not-really-parquet");
        JobResult r = new MaintenanceJob(job(Map.of("task", "compact", "dir", root.toString())))
                .run(dryCtx(audit));
        assertTrue(r.message().contains("has no preview — no action taken"), r.message());
        assertTrue(Files.exists(root.resolve("keep.parquet")), "fail-closed: nothing was touched");
    }

    @Test
    void dbMaintenanceDryRunOnlyDescribes(@TempDir Path audit) throws Exception {
        JobResult r = new MaintenanceJob(job(Map.of("task", "db_maintenance"))).run(dryCtx(audit));
        assertTrue(r.message().contains("db_maintenance[dry-run]"), r.message());
    }

    // ── ledger_prune ─────────────────────────────────────────────────────────────

    @Test
    void ledgerPruneForgetsOldFingerprintsOnly() throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        long old = System.currentTimeMillis() - Duration.ofDays(120).toMillis();
        ledger.record(new LedgerEntry("S", "old.csv", "old.csv", 1, null, null, null, old, old, LedgerEntry.PROCESSED));
        ledger.record(new LedgerEntry("S", "new.csv", "new.csv", 1, null, null, null, old,
                System.currentTimeMillis(), LedgerEntry.PROCESSED));
        ledger.record(new LedgerEntry("OTHER", "old2.csv", "old2.csv", 1, null, null, null, old, old, LedgerEntry.PROCESSED));

        JobResult r = new MaintenanceJob(job(Map.of("task", "ledger_prune",
                "retention_days", "90", "source", "S"))).run();

        assertTrue(r.message().contains("removed 1"), r.message());
        assertTrue(ledger.find("S", "old.csv").isEmpty(), "old fingerprint pruned");
        assertTrue(ledger.find("S", "new.csv").isPresent(), "recent fingerprint kept");
        assertTrue(ledger.find("OTHER", "old2.csv").isPresent(), "other source untouched with a source scope");
    }

    @Test
    void ledgerPruneRequiresRetentionDays() {
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "ledger_prune"))).run(),
                "forgetting must be deliberate — no default retention");
    }

    // ── cleanup policy knobs (MNT-2b) ────────────────────────────────────────────

    /** Write {@code bytes} into {@code dir/name}, mtime backdated by {@code ageDays}. */
    private static Path aged(Path dir, String name, String content, int ageDays) throws Exception {
        Files.createDirectories(dir);
        Path f = Files.writeString(dir.resolve(name), content);
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(Duration.ofDays(ageDays))));
        return f;
    }

    @Test
    void cleanupMaxCountKeepsOnlyTheNewestFiles(@TempDir Path dir) throws Exception {
        Path oldest = aged(dir, "a.csv", "x", 3);
        Path middle = aged(dir, "b.csv", "x", 2);
        Path newest = aged(dir, "c.csv", "x", 1);

        JobResult r = new MaintenanceJob(job(Map.of("task", "cleanup", "dir", dir.toString(),
                "retention_days", "30", "max_count", "2"))).run();

        assertTrue(r.message().contains("deleted 1 file(s)"), r.message());
        assertFalse(Files.exists(oldest), "beyond the newest max_count files");
        assertTrue(Files.exists(middle) && Files.exists(newest));
    }

    @Test
    void cleanupMaxSizeKeepsTheNewestWithinTheByteBudget(@TempDir Path dir) throws Exception {
        Path oldest = aged(dir, "a.csv", "4chr", 3);
        Path middle = aged(dir, "b.csv", "4chr", 2);
        Path newest = aged(dir, "c.csv", "4chr", 1);

        JobResult r = new MaintenanceJob(job(Map.of("task", "cleanup", "dir", dir.toString(),
                "retention_days", "30", "max_size", "8"))).run();

        assertTrue(r.message().contains("deleted 1 file(s), 4 byte(s)"), r.message());
        assertFalse(Files.exists(oldest), "over the newest-first byte budget");
        assertTrue(Files.exists(middle) && Files.exists(newest), "newest files within budget kept");
    }

    @Test
    void cleanupArchivesInsteadOfDeletingAndNeverRewalksTheArchive(@TempDir Path dir) throws Exception {
        Path stale = aged(dir.resolve("sub"), "stale.csv", "old-data", 30);
        Path archiveDir = dir.resolve(".archive");   // inside the cleaned dir — must be excluded from the walk
        JobConfig cfg = job(Map.of("task", "cleanup", "dir", dir.toString(), "retention_days", "7",
                "archive_instead_of_delete", "true", "archive_dir", archiveDir.toString()));

        JobResult first = new MaintenanceJob(cfg).run();
        assertTrue(first.message().contains("archived 1 file(s)"), first.message());
        assertFalse(Files.exists(stale), "moved out of the live tree");
        assertTrue(Files.exists(archiveDir.resolve("sub").resolve("stale.csv")),
                "relative structure preserved under archive_dir");

        JobResult second = new MaintenanceJob(cfg).run();
        assertTrue(second.message().contains("archived 0 file(s)"),
                "the archive itself is never re-cleaned: " + second.message());
        assertTrue(Files.exists(archiveDir.resolve("sub").resolve("stale.csv")));
    }

    @Test
    void cleanupArchiveRequiresAnExplicitArchiveDir(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new MaintenanceJob(job(Map.of(
                "task", "cleanup", "dir", dir.toString(), "archive_instead_of_delete", "true"))).run(),
                "no silent default archive destination");
    }

    // ── runlog_prune (MNT-2a) ────────────────────────────────────────────────────

    /** Write a JSONL file into {@code dir}, mtime backdated by {@code ageDays}. */
    private static Path jsonl(Path dir, String name, int ageDays) throws Exception {
        Files.createDirectories(dir);
        Path f = Files.writeString(dir.resolve(name), "{\"x\":1}\n");
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(Duration.ofDays(ageDays))));
        return f;
    }

    @Test
    void runlogPruneForgetsOldRunHistoryAcrossAllThreeStores(@TempDir Path audit, @TempDir Path ctxDir) throws Exception {
        Path oldLog  = jsonl(audit.resolve("runlog"), "r-old.jsonl", 30);
        Path newLog  = jsonl(audit.resolve("runlog"), "r-new.jsonl", 0);
        Path oldArt  = jsonl(audit.resolve("artifacts"), "r-old.jsonl", 30);
        Path newArt  = jsonl(audit.resolve("artifacts"), "r-new.jsonl", 0);
        DuckDbUtil.loadDriver();
        try (DbJobRunStore store = DbJobRunStore.open("jdbc:duckdb:")) {
            store.record(new JobRun("r-old", "j", "maintenance", "manual",
                    "2020-01-01 00:00:00", "2020-01-01 00:00:01", "SUCCESS", 10L, "m"));
            store.record(new JobRun("r-new", "j", "maintenance", "manual",
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter
                            .ofPattern("yyyy-MM-dd HH:mm:ss")), null, "SUCCESS", 10L, "m"));
            MaintenanceJob job = new MaintenanceJob(job(Map.of("task", "runlog_prune",
                    "retention_days", "7")), null, audit.toString(), store);

            // Dry run first: reports the impact, removes nothing.
            JobResult dry = job.run(dryCtx(ctxDir));
            assertTrue(dry.message().contains(
                    "would remove 1 run log(s), 1 artifact file(s), 1 projected run row(s)"), dry.message());
            assertTrue(Files.exists(oldLog) && Files.exists(oldArt), "dry run must not delete");
            assertEquals(2, store.recentRuns(10, null).size(), "dry run must not prune rows");

            // The real run matches the estimate; recent history survives.
            JobResult real = job.run();
            assertTrue(real.message().contains(
                    "removed 1 run log(s), 1 artifact file(s), 1 projected run row(s)"), real.message());
            assertFalse(Files.exists(oldLog));
            assertFalse(Files.exists(oldArt));
            assertTrue(Files.exists(newLog) && Files.exists(newArt), "recent history kept");
            assertEquals(1, store.recentRuns(10, null).size(), "only the old row pruned");
        }
    }

    @Test
    void runlogPruneMaxCountCapsToNewestFiles(@TempDir Path audit) throws Exception {
        Path oldest = jsonl(audit.resolve("runlog"), "r1.jsonl", 3);
        Path middle = jsonl(audit.resolve("runlog"), "r2.jsonl", 2);
        Path newest = jsonl(audit.resolve("runlog"), "r3.jsonl", 1);

        JobResult r = new MaintenanceJob(job(Map.of("task", "runlog_prune",
                "retention_days", "30", "max_count", "2")), null, audit.toString(), null).run();

        assertTrue(r.message().contains("removed 1 run log(s)"), r.message());
        assertFalse(Files.exists(oldest), "beyond the newest max_count files");
        assertTrue(Files.exists(middle) && Files.exists(newest), "newest N kept");
    }

    @Test
    void runlogPruneRequiresRetentionDays() {
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceJob(job(Map.of("task", "runlog_prune"))).run(),
                "forgetting must be deliberate — no default retention");
    }

    // ── storage_report (MNT-3) ───────────────────────────────────────────────────

    @Test
    void storageReportSummarisesAxesAndFlagsTheThreshold(@TempDir Path space, @TempDir Path ctxDir) throws Exception {
        Files.writeString(Files.createDirectories(space.resolve("config")).resolve("a.toon"), "1234");
        Files.writeString(Files.createDirectories(space.resolve("data")).resolve("big.csv"), "12345678");
        Files.writeString(space.resolve("space.toon"), "12");

        JobResult r = new MaintenanceJob(job(Map.of("task", "storage_report",
                "dir", space.toString(), "warn_bytes", "10"))).run(dryCtx(ctxDir));

        assertTrue(r.message().contains("3 file(s), 14 byte(s)"), r.message());
        assertTrue(r.message().contains(".=2b"), r.message());
        assertTrue(r.message().contains("config=4b"), r.message());
        assertTrue(r.message().contains("data=8b"), r.message());
        assertTrue(r.message().contains("OVER warn_bytes=10"), r.message());
        assertTrue(Files.exists(space.resolve("data").resolve("big.csv")), "read-only task touches nothing");
    }

    @Test
    void storageReportStaysQuietUnderTheThreshold(@TempDir Path space) throws Exception {
        Files.writeString(Files.createDirectories(space.resolve("data")).resolve("small.csv"), "12");
        JobResult r = new MaintenanceJob(job(Map.of("task", "storage_report",
                "dir", space.toString(), "warn_bytes", "1000"))).run();
        assertFalse(r.message().contains("OVER"), r.message());
    }

    // ── scheduler_audit (MNT-4) ──────────────────────────────────────────────────

    /** Poll until the job's last run is terminal, or fail after 10s. */
    private static JobRun await(java.util.function.Supplier<JobRun> latest) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobRun r = latest.get();
            if (r != null && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(25);
        }
        throw new AssertionError("run did not reach a terminal status within 10s");
    }

    @Test
    void schedulerAuditFlagsEveryHygieneFindingClass(@TempDir Path audit) throws Exception {
        JobConfig disabled  = new JobConfig("off", JobType.MAINTENANCE, null, null, false, false, Map.of("task", "heartbeat"));
        JobConfig twinA     = new JobConfig("twin-a", JobType.MAINTENANCE, "0 3 * * *", null, true, false, Map.of("task", "heartbeat"));
        JobConfig twinB     = new JobConfig("twin-b", JobType.MAINTENANCE, "0 3 * * *", null, true, false, Map.of("task", "heartbeat"));
        JobConfig orphanPipe = new JobConfig("waits", JobType.MAINTENANCE, null, "ghost_pipeline", true, false, Map.of("task", "heartbeat"));
        JobConfig orphanSig = new JobConfig("listens", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "heartbeat"), "custom.signal.type", null);
        JobConfig auditJob  = new JobConfig("hygiene", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "scheduler_audit"));
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(disabled, twinA, twinB, orphanPipe, orphanSig, auditJob),
                     new com.gamma.etl.BatchEventBus(), s, null, audit.toString())) {
            js.knownPipelines(() -> java.util.Set.of("real_pipeline"));
            String runId = js.triggerRun("hygiene", null).orElseThrow();
            JobRun run = await(() -> js.lastRunOf("hygiene").orElse(null));

            assertEquals("SUCCESS", run.status(), run.message());
            assertTrue(run.message().contains("finding(s) across 6 job(s)"), run.message());
            List<String> logged = js.runLog(runId).stream().map(RunLogEntry::message).toList();
            assertTrue(logged.stream().anyMatch(m -> m.contains("disabled job 'off'")), logged.toString());
            assertTrue(logged.stream().anyMatch(m -> m.contains("duplicate definition")
                    && m.contains("twin-a") && m.contains("twin-b")), logged.toString());
            assertTrue(logged.stream().anyMatch(m -> m.contains("unknown pipeline 'ghost_pipeline'")), logged.toString());
            assertTrue(logged.stream().anyMatch(m -> m.contains("no declared producer for on_signal 'custom.signal.type'")),
                    logged.toString());
        }
    }

    @Test
    void schedulerAuditIsHealthyOnACleanRegistry(@TempDir Path audit) throws Exception {
        JobConfig ok = new JobConfig("fine", JobType.MAINTENANCE, "0 4 * * *", null, true, false, Map.of("task", "heartbeat"));
        JobConfig listener = new JobConfig("chained", JobType.MAINTENANCE, null, null, true, false,
                Map.of("task", "heartbeat"), "job.run.completed", null);   // a declared framework producer
        JobConfig auditJob = new JobConfig("hygiene", JobType.MAINTENANCE, null, null, true, false, Map.of("task", "scheduler_audit"));
        try (com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(ok, listener, auditJob),
                     new com.gamma.etl.BatchEventBus(), s, null, audit.toString())) {
            js.knownPipelines(() -> java.util.Set.of());
            js.triggerRun("hygiene", null).orElseThrow();
            JobRun run = await(() -> js.lastRunOf("hygiene").orElse(null));
            assertEquals("SUCCESS", run.status(), run.message());
            assertTrue(run.message().contains("0 finding(s)"), run.message());
            assertTrue(run.message().contains("healthy"), run.message());
        }
    }

    // ── backup / backup_verify / restore (MNT-5 / MNT-6, Phase 2) ────────────────

    /** Seed a two-file source tree (one nested) and return the config for a backup of it. */
    private static JobConfig backupCfg(Path source, Path backupDir) throws Exception {
        Files.writeString(Files.createDirectories(source.resolve("orders")).resolve("a.toon"), "alpha");
        Files.writeString(source.resolve("space.toon"), "root");
        return job(Map.of("task", "backup", "dir", source.toString(), "backup_dir", backupDir.toString()));
    }

    private static Path onlyZip(Path backupDir) throws Exception {
        try (var s = Files.list(backupDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip")).findFirst().orElseThrow();
        }
    }

    @Test
    void backupArchivesWithManifestAndDryRunPreviews(@TempDir Path source, @TempDir Path backupDir,
                                                     @TempDir Path ctxDir) throws Exception {
        JobConfig cfg = backupCfg(source, backupDir);

        JobResult dry = new MaintenanceJob(cfg).run(dryCtx(ctxDir));
        assertTrue(dry.message().contains("would archive 2 file(s), 9 byte(s)"), dry.message());
        try (var s = Files.list(backupDir)) {
            assertEquals(0, s.count(), "dry run writes nothing");
        }

        JobResult real = new MaintenanceJob(cfg).run();
        assertTrue(real.message().contains("archived 2 file(s), 9 byte(s)"), real.message());
        Path zip = onlyZip(backupDir);
        assertTrue(Files.isRegularFile(zip.resolveSibling(zip.getFileName() + ".manifest.json")),
                "sidecar manifest written");
    }

    @Test
    void backupVerifyPassesThenDetectsCorruption(@TempDir Path source, @TempDir Path backupDir) throws Exception {
        new MaintenanceJob(backupCfg(source, backupDir)).run();
        JobConfig verify = job(Map.of("task", "backup_verify", "backup_dir", backupDir.toString()));

        JobResult ok = new MaintenanceJob(verify).run();
        assertEquals("SUCCESS", ok.status(), ok.message());
        assertTrue(ok.message().contains("1 archive(s) OK, 2 file entr(ies) hash-checked"), ok.message());

        // Flip bytes inside the archive — verification must fail on the archive hash, fail-closed.
        Path zip = onlyZip(backupDir);
        byte[] bytes = Files.readAllBytes(zip);
        bytes[bytes.length / 2] ^= 0x7f;
        Files.write(zip, bytes);
        JobResult bad = new MaintenanceJob(verify).run();
        assertEquals("FAILED", bad.status(), bad.message());
        assertTrue(bad.message().contains("archive hash mismatch"), bad.message());
    }

    @Test
    void restoreRoundTripsBlocksConflictsAndPreviews(@TempDir Path source, @TempDir Path backupDir,
                                                     @TempDir Path target, @TempDir Path ctxDir) throws Exception {
        new MaintenanceJob(backupCfg(source, backupDir)).run();
        Path zip = onlyZip(backupDir);
        JobConfig restore = job(Map.of("task", "restore",
                "archive", zip.toString(), "target_dir", target.toString()));

        JobResult first = new MaintenanceJob(restore).run();
        assertEquals("SUCCESS", first.status(), first.message());
        assertEquals("alpha", Files.readString(target.resolve("orders").resolve("a.toon")), "byte-identical restore");
        assertEquals("root", Files.readString(target.resolve("space.toon")));

        // Same restore again: everything now conflicts — preview says so, real run blocks fail-closed.
        JobResult dry = new MaintenanceJob(restore).run(dryCtx(ctxDir));
        assertTrue(dry.message().contains("(2 conflict(s))"), dry.message());
        JobResult blocked = new MaintenanceJob(restore).run();
        assertEquals("FAILED", blocked.status(), blocked.message());
        assertTrue(blocked.message().contains("restore blocked: 2 existing file(s)"), blocked.message());

        JobResult forced = new MaintenanceJob(job(Map.of("task", "restore", "archive", zip.toString(),
                "target_dir", target.toString(), "overwrite", "true"))).run();
        assertEquals("SUCCESS", forced.status(), forced.message());
        assertTrue(forced.message().contains("(2 overwritten)"), forced.message());
    }

    @Test
    void backupAppendsCatalogRowAndRegistersTheDataset(@TempDir Path source, @TempDir Path backupDir,
                                                       @TempDir Path dataDir, @TempDir Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            JobResult r = new MaintenanceJob(backupCfg(source, backupDir), dataDir.toString()).run();
            assertEquals("SUCCESS", r.status(), r.message());
            Path storeDir = dataDir.resolve("maintenance_backups");
            try (var s = Files.list(storeDir)) {
                assertEquals(1, s.filter(p -> p.getFileName().toString().endsWith(".parquet")).count(),
                        "one catalog row parquet per backup");
            }
            var store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
            assertTrue(store.exists("dataset", "maintenance_backups"), "catalog Dataset registered");
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── metadata_validate (MNT-7, Phase 2) ───────────────────────────────────────

    @Test
    void metadataValidateFlagsBrokenRefsAndDuplicates(@TempDir Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            var store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
            store.write("widget", "lonely", Map.of("vizType", "bar", "datasetId", "ghost_ds"));
            store.write("dashboard", "board", Map.of("tiles", List.of(Map.of("widgetId", "nope", "span", 1))));
            store.write("transform", "twin_a", Map.of("kind", "expr", "expr", "a+b"));
            store.write("transform", "twin_b", Map.of("kind", "expr", "expr", "a+b"));

            JobResult r = new MaintenanceJob(job(Map.of("task", "metadata_validate"))).run();
            assertEquals("SUCCESS", r.status(), r.message());
            assertTrue(r.message().contains("3 finding(s)"), r.message());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void metadataValidateIsHealthyOnAConsistentRegistry(@TempDir Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            var store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
            store.write("dataset", "orders_ds", Map.of("description", "orders"));
            store.write("widget", "orders_bar", Map.of("vizType", "bar", "datasetId", "orders_ds"));
            store.write("dashboard", "board", Map.of("tiles", List.of(Map.of("widgetId", "orders_bar", "span", 1))));

            JobResult r = new MaintenanceJob(job(Map.of("task", "metadata_validate"))).run();
            assertTrue(r.message().contains("0 finding(s)"), r.message());
            assertTrue(r.message().contains("healthy"), r.message());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── file_repository_audit (MNT-12, Phase 3) ──────────────────────────────────

    @Test
    void fileRepositoryAuditFlagsUnregisteredStoresAndStalePartials(@TempDir Path dataDir,
                                                                    @TempDir Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            var store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
            store.write("dataset", "orders_ds", Map.of("physicalRef", "orders"));
            Files.createDirectories(dataDir.resolve("orders"));
            Files.createDirectories(dataDir.resolve("mystery"));               // no owning dataset
            aged(dataDir.resolve("orders"), "b0_out.parquet.tmp", "x", 3);     // stale partial
            Files.writeString(dataDir.resolve("orders").resolve("fresh.tmp"), "x");   // inside quiet window

            JobResult r = new MaintenanceJob(job(Map.of("task", "file_repository_audit")),
                    dataDir.toString()).run();

            assertTrue(r.message().contains("2 finding(s)"), r.message());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    @Test
    void fileRepositoryAuditIsHealthyOnACleanDataRoot(@TempDir Path dataDir, @TempDir Path writeRoot) throws Exception {
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            var store = new com.gamma.pipeline.ComponentStore(writeRoot.resolve("registry"));
            store.write("dataset", "orders_ds", Map.of("physicalRef", "orders"));
            Files.writeString(Files.createDirectories(dataDir.resolve("orders")).resolve("d_out.parquet"), "x");

            JobResult r = new MaintenanceJob(job(Map.of("task", "file_repository_audit")),
                    dataDir.toString()).run();

            assertTrue(r.message().contains("0 finding(s)"), r.message());
            assertTrue(r.message().contains("healthy"), r.message());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    // ── cleanup min_keep (MNT-2c, Phase 2) ───────────────────────────────────────

    @Test
    void cleanupMinKeepProtectsTheNewestFilesFromEveryLimit(@TempDir Path dir) throws Exception {
        Path oldest = aged(dir, "a.zip", "x", 40);
        Path middle = aged(dir, "b.zip", "x", 35);
        Path newest = aged(dir, "c.zip", "x", 30);

        // Retention alone would delete all three — min_keep pins the newest two.
        JobResult r = new MaintenanceJob(job(Map.of("task", "cleanup", "dir", dir.toString(),
                "retention_days", "7", "min_keep", "2"))).run();

        assertTrue(r.message().contains("deleted 1 file(s)"), r.message());
        assertFalse(Files.exists(oldest));
        assertTrue(Files.exists(middle) && Files.exists(newest), "the newest min_keep files survive");
    }

    // ── db_maintenance ───────────────────────────────────────────────────────────

    @Test
    void dbMaintenanceCoversTheHostsProjectionStores(@TempDir Path audit) throws Exception {
        DuckDbUtil.loadDriver();
        try (DbJobRunStore runStore = DbJobRunStore.open("jdbc:duckdb:");
             com.gamma.util.Scheduler s = new com.gamma.util.Scheduler();
             JobService js = new JobService(List.of(), new com.gamma.etl.BatchEventBus(), s, null,
                     audit.toString(), runStore)) {
            JobResult r = new MaintenanceJob(job(Map.of("task", "db_maintenance")),
                    null, null, runStore, js).run();
            assertTrue(r.message().contains("2 store(s) maintenance completed"), r.message());
        }
    }

    // ── db_maintenance (ledger) ──────────────────────────────────────────────────

    @Test
    void dbMaintenanceRunsOnDbLedger(@TempDir Path dir) throws Exception {
        String url = "jdbc:duckdb:" + dir.resolve("ledger.db").toString().replace('\\', '/');
        try (var db = com.gamma.acquire.DbAcquisitionLedger.open(url, null, null)) {
            AcquisitionLedgers.use(db);
            JobResult r = new MaintenanceJob(job(Map.of("task", "db_maintenance"))).run();
            assertTrue(r.message().contains("completed"), r.message());
        }
    }

    // ── compact ──────────────────────────────────────────────────────────────────

    /** Write one small parquet file with {@code rows} rows into {@code dir}, mtime backdated 2 days. */
    private static Path smallParquet(Connection conn, Path dir, String name, int rows, int offset) throws Exception {
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        try (Statement st = conn.createStatement()) {
            st.execute("COPY (SELECT range + " + offset + " AS id FROM range(" + rows + ")) TO '"
                    + f.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        Files.setLastModifiedTime(f, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
        return f;
    }

    private static long countRows(Connection conn, Path dir) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*), count(DISTINCT id) FROM read_parquet('"
                     + dir.toAbsolutePath().toString().replace('\\', '/') + "/*.parquet')")) {
            assertTrue(rs.next());
            assertEquals(rs.getLong(1), rs.getLong(2), "no duplicated rows");
            return rs.getLong(1);
        }
    }

    @Test
    void compactMergesOldSmallFilesAndPreservesRows(@TempDir Path root) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            Path partition = root.resolve("year=2026/month=07/day=01");
            for (int i = 0; i < 3; i++) smallParquet(conn, partition, "b" + i + "_out.parquet", 10, i * 10);
            // A fresh file (mtime now) must survive untouched — it is inside the quiet window.
            Path fresh = partition.resolve("fresh_out.parquet");
            try (Statement st = conn.createStatement()) {
                st.execute("COPY (SELECT range + 100 AS id FROM range(5)) TO '"
                        + fresh.toAbsolutePath().toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }

            JobResult r = new MaintenanceJob(job(Map.of("task", "compact",
                    "dir", root.toString(), "min_age_days", "1", "min_files", "2"))).run();

            assertTrue(r.message().contains("merged 3 file(s) across 1 partition dir(s)"), r.message());
            List<Path> left;
            try (var s = Files.list(partition)) { left = s.sorted().toList(); }
            assertEquals(2, left.size(), "one merged file + the fresh one: " + left);
            assertTrue(left.stream().anyMatch(p -> p.getFileName().toString().startsWith("compacted_")));
            assertTrue(left.contains(fresh), "fresh file inside the quiet window is untouched");
            assertEquals(35, countRows(conn, partition), "30 merged + 5 fresh rows, none lost or duplicated");
        }
    }

    @Test
    void compactSkipsPartitionsBelowMinFiles(@TempDir Path root) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            Path partition = root.resolve("year=2026/month=07/day=02");
            smallParquet(conn, partition, "only_out.parquet", 10, 0);

            JobResult r = new MaintenanceJob(job(Map.of("task", "compact",
                    "dir", root.toString(), "min_files", "2"))).run();

            assertTrue(r.message().contains("merged 0 file(s)"), r.message());
            assertTrue(Files.exists(partition.resolve("only_out.parquet")), "small partitions left alone");
        }
    }

    @Test
    void healRestoresOriginalsFromAnInterruptedSwap(@TempDir Path root) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            Path partition = root.resolve("year=2026/month=07/day=03");
            Path f = smallParquet(conn, partition, "b0_out.parquet", 10, 0);
            // Simulate a crash after the originals were hidden but before the merged target was revealed.
            Files.move(f, partition.resolve("b0_out.parquet.compacting"));
            Files.write(partition.resolve(".compact-journal"),
                    List.of("compacted_123_out.parquet", "b0_out.parquet"));

            JobResult r = new MaintenanceJob(job(Map.of("task", "compact", "dir", root.toString()))).run();

            assertNotNull(r);
            assertTrue(Files.exists(f), "hidden original restored by heal");
            assertFalse(Files.exists(partition.resolve(".compact-journal")), "journal consumed");
            assertEquals(10, countRows(conn, partition), "no rows lost");
        }
    }
}
