package com.gamma.inspector;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CollectorProcessorPollTest {

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

        CollectorProcessor.run(cfg);

        // All 6 tiny files consolidate into ONE partition's single output file.
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertEquals(1, w.filter(p -> p.getFileName().toString().endsWith("_out.csv")).count());
        }
        // One batch row recorded.
        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(2, batches.split("\n").length, "header + 1 batch row");
        // Re-running is a no-op: markers skip all files (still exactly one output file).
        CollectorProcessor.run(cfg);
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
        assertEquals(0, CollectorProcessor.countPending(cfg), "no inbox → nothing pending");
        assertFalse(Files.exists(Path.of(cfg.dirs().batchesFilePath())), "counting must not write audit");

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (int i = 0; i < 6; i++)
            Files.writeString(inbox.resolve("f" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nr" + i + ",1.0,2020-04-03\n");

        // All 6 are pending before any run; the scan is read-only (no batch rows written).
        assertEquals(6, CollectorProcessor.countPending(cfg), "6 unprocessed files pending");
        assertFalse(Files.exists(Path.of(cfg.dirs().batchesFilePath())), "counting must not process");

        CollectorProcessor.run(cfg);                              // process all 6 → markers written
        assertEquals(0, CollectorProcessor.countPending(cfg), "all processed → none pending");

        // New arrivals are pending again; previously-marked files stay excluded.
        for (int i = 0; i < 2; i++)
            Files.writeString(inbox.resolve("g" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nn" + i + ",2.0,2020-04-04\n");
        assertEquals(2, CollectorProcessor.countPending(cfg), "only the 2 new files pending");
    }

    @Test
    void oldestInboxAgeSecondsReflectsLagAndIsZeroWhenEmpty(@TempDir Path dir) throws Exception {
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        String pollDir = cfg.dirs().poll();

        // Empty/absent inbox → 0 lag (and blank/missing dirs are safe).
        assertEquals(0.0, CollectorProcessor.oldestInboxAgeSeconds(pollDir), "no inbox → no lag");
        assertEquals(0.0, CollectorProcessor.oldestInboxAgeSeconds(null), "null dir → no lag");

        Path inbox = Path.of(pollDir);
        Files.createDirectories(inbox);
        Path f = inbox.resolve("waiting.csv");
        Files.writeString(f, "ID,AMT,EVENT_DATE\nr,1.0,2020-04-03\n");
        // Backdate the file 10 minutes so the age is a stable, positive value.
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 600_000));

        double age = CollectorProcessor.oldestInboxAgeSeconds(pollDir);
        assertTrue(age >= 590 && age <= 660, "≈600s lag for a 10-min-old waiting file, was " + age);
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

        CollectorProcessor.run(cfg);                              // processes all 30 → 1 batch
        String afterFirst = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(2, afterFirst.split("\n").length, "header + 1 batch row");

        // No new files: parallel scan finds every candidate already marked → no-op.
        CollectorProcessor.run(cfg);
        assertEquals(afterFirst, Files.readString(Path.of(cfg.dirs().batchesFilePath())),
                "re-run with all files marked must add no batch rows");

        // Add 4 new files among the 30 marked ones: only the new ones get processed.
        for (int i = 0; i < 4; i++)
            Files.writeString(inbox.resolve("b" + i + ".csv"),
                    "ID,AMT,EVENT_DATE\nn" + i + ",2.0,2020-04-04\n");
        CollectorProcessor.run(cfg);
        String afterThird = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertEquals(3, afterThird.split("\n").length, "header + 2 batch rows (1 per run that found work)");
    }

    @Test
    void reprocessesChangedFileInChecksumModeWithMarkersDirConfigured(@TempDir Path dir) throws Exception {
        // Regression: in content-based dedup (checksum/metadata) the fingerprint ledger is the source of
        // truth, NOT the path-marker sentinel. The engine must skip the marker write — otherwise re-ingesting
        // a CHANGED file at a known path would hit the prior commit's <file>.processed marker
        // (FileAlreadyExistsException) and the reprocess would fail. A markers: dir is configured here on
        // purpose to prove markers are skipped (not just absent) in checksum mode.
        AcquisitionLedgers.use(new InMemoryAcquisitionLedger());   // isolate the process-wide ledger

        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        String toon = """
            name: CHECKSUM_MARK_ETL
            active: true
            version: 1
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            collector:
              duplicate:
                mode: checksum
                algorithm: SHA256
                on_change: reprocess
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          schema.toString().replace("\\", "/"));
        Path toonP = dir.resolve("cm_pipeline.toon");
        Files.writeString(toonP, toon);
        PipelineConfig cfg = PipelineConfig.load(toonP.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);

        // v1 → processed; its fingerprint is recorded in the ledger.
        Files.writeString(inbox.resolve("ORDERS.csv"), "ID,AMT,EVENT_DATE\nr0,1.0,2020-04-03\n");
        CollectorProcessor.run(cfg);

        // The marker write must be skipped in checksum mode — no <file>.processed sentinel.
        Path markersDir = Path.of(cfg.dirs().markers());
        if (Files.exists(markersDir)) {
            try (Stream<Path> w = Files.walk(markersDir)) {
                assertEquals(0, w.filter(p -> p.toString().endsWith(".processed")).count(),
                        "checksum mode must not write path markers (the ledger is the source of truth)");
            }
        }

        // v2 → same path, CHANGED content (extra row) → checksum differs from the ledger → CHANGED → reprocess.
        Files.writeString(inbox.resolve("ORDERS.csv"),
                "ID,AMT,EVENT_DATE\nr0,1.0,2020-04-03\nr1,2.0,2020-04-03\n");
        CollectorProcessor.run(cfg);

        String batches = Files.readString(Path.of(cfg.dirs().batchesFilePath()));
        assertFalse(batches.contains("FAILED"),
                "reprocess of a CHANGED file must not fail on a stale path marker");
        assertEquals(3, batches.split("\n").length,
                "header + 2 successful batch rows (v1 + the reprocessed v2)");
    }

    @Test
    void quarantinesAZeroRowFileSoItIsNotReprocessedForever(@TempDir Path dir) throws Exception {
        // A readable file that yields ZERO ingestable rows (here: header-only) used to produce an
        // EMPTY batch that was neither backed up nor marked, so the poll loop rediscovered and
        // reprocessed it every cycle forever. It must instead be consumed (quarantined under `empty`).
        Path toon = PipelineConfigBatchTestRef.writePipeline(dir, "");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path emptyFile = inbox.resolve("empty.csv");
        Files.writeString(emptyFile, "ID,AMT,EVENT_DATE\n");   // header only → zero data rows

        CollectorProcessor.run(cfg);

        // Consumed: moved out of the inbox into quarantine/<reason=empty>/empty.csv.
        assertFalse(Files.exists(emptyFile), "0-row file must be moved out of the inbox");
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().quarantine()))) {
            assertTrue(w.anyMatch(p -> p.getFileName().toString().equals("empty.csv")
                            && p.getParent() != null
                            && p.getParent().getFileName().toString().equals("empty")),
                    "0-row file should be quarantined under the 'empty' reason");
        }

        // Second poll: the inbox is empty, so no new batch is planned — the loop is broken.
        int linesAfterFirst = batchLineCount(cfg);
        CollectorProcessor.run(cfg);
        assertEquals(linesAfterFirst, batchLineCount(cfg),
                "the quarantined empty file must not be rediscovered and reprocessed");
    }

    private static int batchLineCount(PipelineConfig cfg) throws java.io.IOException {
        Path f = Path.of(cfg.dirs().batchesFilePath());
        return Files.exists(f) ? Files.readString(f).split("\n").length : 0;
    }
}
