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

    @AfterEach
    void resetLedger() {
        AcquisitionLedgers.use(null);
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

    // ── db_maintenance ───────────────────────────────────────────────────────────

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
