package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the M5 database-backed {@link DbStatusStore} against a real DuckDB in-memory
 * engine (the bundled JDBC driver — no external server needed). The same engine-neutral
 * JDBC code path runs on PostgreSQL in production; these tests pin the SQL + the
 * file→DB projection ({@link DbStatusStore#sync}) and prove the DB read surface matches
 * the file-backed store the Control API/observability used before M5.
 */
class DbStatusStoreTest {

    private Connection conn;
    private DbStatusStore db;

    @BeforeEach
    void openDb() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        conn = DriverManager.getConnection("jdbc:duckdb:");   // in-memory, lives with this connection
        db = new DbStatusStore(conn);
    }

    @AfterEach
    void closeDb() {
        if (db != null) db.close();
    }

    /** Run a real Stage-1 batch so the on-disk audit artifacts exist to project from. */
    private PipelineConfig runOnePipeline(Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        try (SourceService svc = new SourceService(List.of(toon), 3600, 1)) {
            assertEquals(0, svc.runAllOnce().failed(), "the seeded batch should commit cleanly");
        }
        return PipelineConfig.load(toon.toString());
    }

    @Test
    void syncProjectsFileAuditAndReadsMatchTheFileStore(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();

        db.sync(file, List.of(cfg));

        // committed batches round-trip exactly
        Set<String> fileCommits = file.committedBatches(cfg);
        assertFalse(fileCommits.isEmpty(), "the run committed at least one batch");
        assertEquals(fileCommits, db.committedBatches(cfg), "DB commits match the commit log");

        // batch / file / lineage row counts and content match the file store
        assertEquals(file.batches(cfg).size(), db.batches(cfg).size(), "batch row count matches");
        assertEquals(file.files(cfg).size(),   db.files(cfg).size(),   "file row count matches");
        assertEquals(file.lineage(cfg, null),  db.lineage(cfg, null),  "lineage rows match verbatim");
        assertFalse(db.batches(cfg).isEmpty(), "batch audit projected");

        // a projected row preserves its columns (ordered map → JSON → ordered map)
        Map<String, String> first = db.batches(cfg).get(0);
        assertEquals(file.batches(cfg).get(0), first, "row map round-trips faithfully");
    }

    @Test
    void reSyncIsIdempotent(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();

        db.sync(file, List.of(cfg));
        int batches = db.batches(cfg).size();
        int commits = db.committedBatches(cfg).size();

        db.sync(file, List.of(cfg));   // refresh — DELETE-then-INSERT, no duplication
        assertEquals(batches, db.batches(cfg).size(), "re-sync does not duplicate batch rows");
        assertEquals(commits, db.committedBatches(cfg).size(), "re-sync does not duplicate commits");
    }

    @Test
    void lineageFiltersByBatchId(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        FileStatusStore file = new FileStatusStore();
        db.sync(file, List.of(cfg));

        String batchId = db.committedBatches(cfg).iterator().next();
        List<Map<String, String>> filtered = db.lineage(cfg, batchId);
        assertFalse(filtered.isEmpty(), "lineage rows exist for the committed batch");
        assertTrue(filtered.stream().allMatch(r -> batchId.equals(r.get("batch_id"))),
                "every filtered lineage row belongs to the requested batch");
        // an unknown batch yields nothing
        assertTrue(db.lineage(cfg, "no-such-batch").isEmpty());
    }

    @Test
    void sourceServiceWithDbBackendServesDataThroughTheReadSurface(@TempDir Path dir) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path inbox = dir.resolve("inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"),
                "ID,AMT,EVENT_DATE\n1,10,2020-01-01\n2,20,2020-01-01\n3,30,2020-02-05\n");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // a dedicated connection: SourceService.close() owns and closes the DB store
        Class.forName("org.duckdb.DuckDBDriver");
        Connection svcConn = DriverManager.getConnection("jdbc:duckdb:");
        DbStatusStore backend = new DbStatusStore(svcConn);
        try (SourceService svc =
                     new SourceService(List.of(toon), List.of(), 3600, 1, backend)) {
            assertEquals(0, svc.runAllOnce().failed());
            // runAllOnce projects the new commit into the DB; the API/observability read here
            assertSame(backend, svc.statusStore(), "the DB store is the service's read surface");
            assertFalse(svc.statusStore().committedBatches(cfg).isEmpty(),
                    "committed batch is queryable from the DB after the cycle");
            assertFalse(svc.statusStore().batches(cfg).isEmpty(), "batch audit projected to the DB");
            // observability reads committed count through the same surface
            assertEquals(1, svc.pipelines().get(0).committedBatches());
        }
    }

    @Test
    void freshStoreReadsEmptyBeforeAnySync(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = runOnePipeline(dir);
        // schema initialised, but no sync yet → all reads empty, never throwing
        assertTrue(db.committedBatches(cfg).isEmpty());
        assertTrue(db.batches(cfg).isEmpty());
        assertTrue(db.files(cfg).isEmpty());
        assertTrue(db.lineage(cfg, null).isEmpty());
        assertTrue(db.quarantine(cfg).isEmpty());
    }
}
