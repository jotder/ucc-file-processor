package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SourceProcessorPollTest {

    @Test
    void consolidatesManySmallFilesIntoOneBatch(@TempDir Path dir) throws Exception {
        String batch = """
              batch:
                max_files: 100
                max_bytes: 268435456
            """;
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, batch);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 6; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        SourceProcessor.run(cfg);

        // All 6 tiny files consolidate into ONE partition's single output file.
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
        // One batch row recorded.
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(2, batches.split("\n").length, "header + 1 batch row");
        // Re-running is a no-op: markers skip all files (still exactly one output file).
        SourceProcessor.run(cfg);
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
    }

    @Test
    void countPendingReflectsUnprocessedInboxAndIsReadOnly(@TempDir Path dir) throws Exception {
        String batch = """
              batch:
                max_files: 100
                max_bytes: 268435456
            """;
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, batch);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // No inbox yet → read-only scan returns 0 (and creates nothing).
        assertEquals(0, SourceProcessor.countPending(cfg), "no inbox → nothing pending");
        assertFalse(Files.exists(Path.of(cfg.dirs().batchesFilePath())), "counting must not write audit");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 6; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        // All 6 are pending before any run; the scan is read-only (no batch rows written).
        assertEquals(6, SourceProcessor.countPending(cfg), "6 unprocessed files pending");
        assertFalse(Files.exists(Path.of(cfg.dirs().batchesFilePath())), "counting must not process");

        SourceProcessor.run(cfg);                              // process all 6 → markers written
        assertEquals(0, SourceProcessor.countPending(cfg), "all processed → none pending");

        // New arrivals are pending again; previously-marked files stay excluded.
        for (int i = 0; i < 2; i++)
            Files.writeString(inbox.resolve("g" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nn" + i + ",2.0,2020-04-04\n");
        assertEquals(2, SourceProcessor.countPending(cfg), "only the 2 new files pending");
    }

    @Test
    void parallelScanSkipsAlreadyProcessedAndPicksUpNewFiles(@TempDir Path dir) throws Exception {
        // threads > 1 routes the candidate scan through the parallel duplicate-check
        // path; verify it still skips marked files exactly and only the newly-arrived
        // files form a fresh batch on the next poll.
        String batch = """
              batch:
                max_files: 100
                max_bytes: 268435456
            """;
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, batch);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertTrue(cfg.processing().threads() > 1, "default config should use > 1 thread");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 30; i++)
            Files.writeString(inbox.resolve("a" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        SourceProcessor.run(cfg);                              // processes all 30 → 1 batch
        String afterFirst = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(2, afterFirst.split("\n").length, "header + 1 batch row");

        // No new files: parallel scan finds every candidate already marked → no-op.
        SourceProcessor.run(cfg);
        assertEquals(afterFirst, Files.readString(Path.of(cfg.dirs().batchesFilePath())),
                "re-run with all files marked must add no batch rows");

        // Add 4 new files among the 30 marked ones: only the new ones get processed.
        for (int i = 0; i < 4; i++)
            Files.writeString(inbox.resolve("b" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nn" + i + ",2.0,2020-04-04\n");
        SourceProcessor.run(cfg);
        String afterThird = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(3, afterThird.split("\n").length, "header + 2 batch rows (1 per run that found work)");
    }
}
