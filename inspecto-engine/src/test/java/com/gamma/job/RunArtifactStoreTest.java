package com.gamma.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1d Run Artifacts (§10): recording through {@code ctx.artifacts()} persists to JSONL and reads back
 *  with the {@link ResultSetMeta} intact — the round trip the Control API + $upstream depend on. */
class RunArtifactStoreTest {

    @Test
    void recordsAndReadsBackDatasetAndFileArtifacts(@TempDir Path dir) {
        RunArtifactStore store = new RunArtifactStore(dir.toString());
        RunContext ctx = new RunContext("r1", "default", "loader", "manual", "r1", 0,
                Map.of(), new RunLogStore(dir.toString()), 100, store);

        ResultSetMeta meta = new ResultSetMeta(List.of(
                new ResultSetMeta.Column("account_id", "BIGINT", ResultSetMeta.Role.DIMENSION),
                new ResultSetMeta.Column("total", "DECIMAL", ResultSetMeta.Role.MEASURE)));
        ctx.artifacts().dataset("output", "txn_rollup", meta, 4200L, Instant.parse("2026-07-07T06:00:04Z"));
        ctx.artifacts().file("export", dir.resolve("out.csv"), 1024L);

        List<RunArtifact> arts = store.read("r1");
        assertEquals(2, arts.size(), "both artifacts persisted");

        RunArtifact d = arts.get(0);
        assertEquals("dataset", d.kind());
        assertEquals("txn_rollup", d.ref());
        assertEquals(4200L, d.rows());
        assertEquals("2026-07-07T06:00:04Z", d.watermark());
        assertEquals(1, d.seq());
        assertNotNull(d.resultSet());
        assertEquals(2, d.resultSet().columns().size());
        assertEquals(ResultSetMeta.Role.MEASURE, d.resultSet().columns().get(1).role(),
                "ResultSetMeta (columns + roles) survives the JSON round trip");

        RunArtifact f = arts.get(1);
        assertEquals("file", f.kind());
        assertEquals(1024L, f.bytes());
        assertNull(f.resultSet(), "a file artifact has no result-set shape");
        assertEquals(2, f.seq(), "seq is monotonic across a run's artifacts");
    }

    @Test
    void unknownRunReadsEmpty(@TempDir Path dir) {
        assertTrue(new RunArtifactStore(dir.toString()).read("never-ran").isEmpty());
    }
}
