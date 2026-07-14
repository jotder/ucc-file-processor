package com.gamma.etl;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.Checksums;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.acquire.LocalFileSystemConnector;
import com.gamma.acquire.CollectorConnector;
import com.gamma.acquire.CollectorConnectors;
import com.gamma.inspector.CollectorProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Phase-A config: the additive {@code source:} block + connector resolution (and legacy default). */
class SourceConfigTest {

    /** Minimal pipeline with an optional top-level {@code source:} block injected at {@code sourceBlock}. */
    private static Path writePipeline(Path dir, String sourceBlock) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String toon = """
            name: SRC_ETL
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
            %s
            output:
              format: CSV
            processing:
              threads: 2
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(dir, dir, dir, dir, dir, dir, dir, dir, dir,
                          sourceBlock, schema.toString().replace("\\", "/"));
        Path p = dir.resolve("src_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void noSourceBlockDefaultsToLocalWithLegacyDiscovery(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        PipelineConfig.Collector s = cfg.collector();
        assertEquals("src_etl", s.id());
        assertEquals("local", s.connector());
        assertEquals(List.of("glob:**/*.csv"), s.includes(), "includes default to processing.file_pattern");
        assertTrue(s.excludes().isEmpty());
        assertEquals(-1, s.recursiveDepth(), "unbounded by default");
        try (CollectorConnector c = CollectorConnectors.forConfig(cfg)) {
            assertInstanceOf(LocalFileSystemConnector.class, c);
        }
    }

    @Test
    void sourceBlockOverridesDiscovery(@TempDir Path dir) throws Exception {
        String block = """
            collector:
              id: CDR_LOCAL
              connector: local
              include[1]: "glob:**/*.dat"
              exclude[2]: "*.tmp", "*.partial"
              recursive_depth: 2
            """;
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, block).toString());
        PipelineConfig.Collector s = cfg.collector();
        assertEquals("CDR_LOCAL", s.id());
        assertEquals("local", s.connector());
        assertEquals(List.of("glob:**/*.dat"), s.includes());
        assertEquals(List.of("*.tmp", "*.partial"), s.excludes());
        assertEquals(2, s.recursiveDepth());
    }

    @Test
    void unknownConnectorFailsFastWithClearMessage(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              connector: sftp
            """).toString());
        assertEquals("sftp", cfg.collector().connector());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> CollectorConnectors.forConfig(cfg));
        assertTrue(ex.getMessage().contains("sftp"), ex.getMessage());
    }

    @Test
    void sourceConnectionBindingParses(@TempDir Path dir) throws Exception {
        PipelineConfig none = PipelineConfig.load(writePipeline(dir, "").toString());
        assertNull(none.collector().connection(), "no source block ⇒ no connection binding");
        assertFalse(none.collector().hasConnection());

        PipelineConfig bound = PipelineConfig.load(writePipeline(dir, """
            collector:
              connector: sftp
              connection: CDR_SFTP_PROD
            """).toString());
        assertEquals("CDR_SFTP_PROD", bound.collector().connection());
        assertTrue(bound.collector().hasConnection());
    }

    @Test
    void metadataDedupSkipsUnchangedAndReprocessesChanged(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                collector:
                  id: META_SRC
                  duplicate:
                    mode: METADATA
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Path f = inbox.resolve("a.csv");
            Files.writeString(f, "x");

            // first sight: no ledger entry ⇒ NEW ⇒ a candidate
            assertEquals(List.of("a.csv"), candidateNames(cfg));

            // simulate the post-commit record of the current fingerprint
            ledger.record(LedgerEntry.metadata("META_SRC", "a.csv", "a.csv",
                    Files.size(f), Files.getLastModifiedTime(f).toMillis(), 1L));
            // unchanged ⇒ DUPLICATE ⇒ skipped
            assertTrue(CollectorProcessor.collectCandidates(cfg).isEmpty(), "unchanged file is a duplicate");

            // content changes (size differs) ⇒ CHANGED ⇒ reprocessed (default on_change = reprocess)
            Files.writeString(f, "xxxxx");
            assertEquals(List.of("a.csv"), candidateNames(cfg), "a changed file is reprocessed");
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());   // reset the process-wide ledger
        }
    }

    @Test
    void fullRunRecordsFingerprintToLedger(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                collector:
                  id: RUN_SRC
                  duplicate:
                    mode: METADATA
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\nr,1,2020-04-03\n");

            CollectorProcessor.run(cfg);   // a full ingest cycle

            assertTrue(ledger.find("RUN_SRC", "a.csv").isPresent(),
                    "the fingerprint is recorded once the batch commits");
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        }
    }

    @Test
    void checksumDetectsContentChangeEvenWhenSizeAndMtimeUnchanged(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                collector:
                  id: CS_SRC
                  duplicate:
                    mode: CHECKSUM
                    algorithm: SHA256
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Path f = inbox.resolve("a.csv");
            Files.writeString(f, "ID,AMT,EVENT_DATE\nr,1,2020-04-03\n");
            var mtime = Files.getLastModifiedTime(f);
            long size = Files.size(f);
            String originalCs = Checksums.of(f, "SHA256");

            // pretend it was already processed with the original content
            ledger.record(new LedgerEntry("CS_SRC", "a.csv", "a.csv", size, originalCs, null, null,
                    mtime.toMillis(), 1L, LedgerEntry.PROCESSED));

            // content changes (same byte length), mtime forced back — METADATA would call this a duplicate
            Files.writeString(f, "ID,AMT,EVENT_DATE\nr,2,2020-04-03\n");
            Files.setLastModifiedTime(f, mtime);
            assertEquals(size, Files.size(f));

            // the real run path hashes the file, sees a different digest ⇒ CHANGED ⇒ reprocessed + re-recorded
            CollectorProcessor.run(cfg);

            String afterCs = ledger.find("CS_SRC", "a.csv").orElseThrow().checksum();
            assertNotEquals(originalCs, afterCs,
                    "checksum mode reprocessed a content change that size+mtime hide");
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        }
    }

    @Test
    void checksumFullRunRecordsTheContentHash(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                collector:
                  id: CSRUN_SRC
                  duplicate:
                    mode: CHECKSUM
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\nr,1,2020-04-03\n");

            CollectorProcessor.run(cfg);

            LedgerEntry e = ledger.find("CSRUN_SRC", "a.csv").orElseThrow();
            assertNotNull(e.checksum(), "CHECKSUM mode records the content hash post-commit");
            assertFalse(e.checksum().isBlank());
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        }
    }

    private static List<String> candidateNames(PipelineConfig cfg) throws Exception {
        return CollectorProcessor.collectCandidates(cfg).stream().map(File::getName).sorted().toList();
    }

    // ── Phase C4: incremental high-watermark ──────────────────────────────────────────────────

    @Test
    void incrementalBlockParsesWatermark(@TempDir Path dir) throws Exception {
        PipelineConfig none = PipelineConfig.load(writePipeline(dir, "").toString());
        assertFalse(none.collector().incremental().enabled(), "absent ⇒ full listing");
        assertEquals(PipelineConfig.Incremental.DISABLED, none.collector().incremental());

        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              duplicate:
                mode: METADATA
              incremental:
                watermark: last_modified
            """).toString());
        assertTrue(cfg.collector().incremental().enabled());
        assertEquals("last_modified", cfg.collector().incremental().watermark());
    }

    @Test
    void watermarkSkipsFilesOlderThanTheLedgerHighWatermark(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                collector:
                  id: WM_SRC
                  include[1]: "glob:**/*.csv"
                  duplicate:
                    mode: METADATA
                  incremental:
                    watermark: last_modified
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            writeWithMtime(inbox.resolve("old.csv"), 1_000L);
            writeWithMtime(inbox.resolve("edge.csv"), 3_000L);   // == watermark ⇒ passes (frontier re-examined)
            writeWithMtime(inbox.resolve("new.csv"), 5_000L);

            // seed the high-watermark at 3_000 via a previously-recorded (different) file for this source
            ledger.record(LedgerEntry.metadata("WM_SRC", "seed.csv", "seed.csv", 1L, 3_000L, 1L));

            assertEquals(List.of("edge.csv", "new.csv"), candidateNames(cfg),
                    "files modified strictly before the watermark are skipped; the frontier and newer pass");
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        }
    }

    @Test
    void watermarkIsOptInSoOldFilesAreSeenWithoutTheBlock(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                collector:
                  id: NOWM_SRC
                  include[1]: "glob:**/*.csv"
                  duplicate:
                    mode: METADATA
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            writeWithMtime(inbox.resolve("old.csv"), 1_000L);
            ledger.record(LedgerEntry.metadata("NOWM_SRC", "seed.csv", "seed.csv", 1L, 9_999L, 1L));

            assertEquals(List.of("old.csv"), candidateNames(cfg),
                    "without source.incremental, a high ledger watermark never filters (the knob is opt-in)");
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        }
    }

    private static void writeWithMtime(Path file, long mtimeMillis) throws Exception {
        Files.writeString(file, "ID,AMT,EVENT_DATE\nr,1,2020-04-03\n");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(mtimeMillis));
    }

    @Test
    void duplicateBlockParsesModeAlgorithmAndOnChange(@TempDir Path dir) throws Exception {
        PipelineConfig none = PipelineConfig.load(writePipeline(dir, "").toString());
        assertEquals("path", none.collector().duplicate().mode(), "absent block ⇒ path (legacy)");
        assertFalse(none.collector().duplicate().contentBased());

        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              duplicate:
                mode: CHECKSUM
                algorithm: SHA256
                on_change: REPROCESS
            """).toString());
        PipelineConfig.Duplicate d = cfg.collector().duplicate();
        assertEquals("checksum", d.mode());
        assertEquals("SHA256", d.algorithm());
        assertEquals("reprocess", d.onChange());
        assertTrue(d.contentBased());
    }

    @Test
    void noStabilityBlockIsDisabled(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        assertFalse(cfg.collector().stability().enabled());
        assertEquals(PipelineConfig.Stability.DISABLED, cfg.collector().stability());
    }

    @Test
    void stabilityBlockParsesWindowChecksMarkerAndTempExclusion(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              stability:
                window: 5m
                size_checks: 3
                ready_marker: "{name}.ok"
                exclude_temp_files: true
            """).toString());
        PipelineConfig.Stability st = cfg.collector().stability();
        assertTrue(st.enabled());
        assertEquals(300_000L, st.windowMillis(), "5m → 300_000ms");
        assertEquals(3, st.sizeChecks());
        assertEquals("{name}.ok", st.readyMarker());
        assertTrue(st.excludeTempFiles());
        assertTrue(st.tempPatterns().contains("*.tmp"), "default temp patterns applied");
    }

    @Test
    void collectCandidatesGatesByReadyMarkerAndExcludesTempFiles(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              include[1]: "glob:**/*"
              stability:
                ready_marker: "{name}.done"
            """).toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("data.csv"), "x");
        Files.writeString(inbox.resolve("data.csv.done"), "");   // sentinel ⇒ data.csv is READY
        Files.writeString(inbox.resolve("pending.csv"), "x");    // no sentinel ⇒ held (NOT_READY)
        Files.writeString(inbox.resolve("scratch.tmp"), "x");    // temp-file ⇒ excluded at discovery

        List<String> names = CollectorProcessor.collectCandidates(cfg).stream()
                .map(File::getName).sorted().toList();
        assertEquals(List.of("data.csv"), names,
                "only the marked file is ready; pending is held, the .tmp is excluded, the .done sentinel is not a candidate");
    }

    @Test
    void collectCandidatesHonoursExcludeAndRecursiveDepth(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              include[1]: "glob:**/*"
              exclude[1]: "*.tmp"
              recursive_depth: 1
            """).toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox.resolve("sub"));
        Files.writeString(inbox.resolve("top.csv"), "x");          // depth 1, included
        Files.writeString(inbox.resolve("skip.tmp"), "x");         // depth 1, excluded by *.tmp
        Files.writeString(inbox.resolve("sub/deep.csv"), "x");     // depth 2, excluded by recursive_depth:1

        List<String> names = CollectorProcessor.collectCandidates(cfg).stream()
                .map(File::getName).sorted().toList();
        assertEquals(List.of("top.csv"), names);
    }

    // ── Phase D: collection guarantee + sequence-gap detection ─────────────────────────────────

    @Test
    void guaranteeAndGapDetectionParse(@TempDir Path dir) throws Exception {
        PipelineConfig none = PipelineConfig.load(writePipeline(dir, "").toString());
        assertEquals(PipelineConfig.Guarantee.BEST_EFFORT, none.collector().guarantee(), "absent ⇒ best-effort");
        assertFalse(none.collector().gapDetection().active(), "absent ⇒ no gap detection");

        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              guarantee: EXACTLY_ONCE
              duplicate:
                mode: CHECKSUM
              gap_detection:
                sequence: "CDR_{yyyyMMddHH}"
            """).toString());
        assertEquals(PipelineConfig.Guarantee.EXACTLY_ONCE, cfg.collector().guarantee());
        assertTrue(cfg.collector().guarantee().requiresLedger());
        assertTrue(cfg.collector().gapDetection().active());
        assertEquals("CDR_{yyyyMMddHH}", cfg.collector().gapDetection().sequence());
    }

    @Test
    void gapDetectionEmitsSequenceGapOnTheRunPath(@TempDir Path dir) throws Exception {
        com.gamma.event.InMemoryEventStore events = new com.gamma.event.InMemoryEventStore(1000);
        com.gamma.event.EventLog.global().installStore(events);
        com.gamma.acquire.GapTracker.shared().reset("GAP_SRC");

        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            collector:
              id: GAP_SRC
              gap_detection:
                sequence: "cdr_{yyyyMMddHH}.csv"
            """).toString());
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        for (String hh : List.of("00", "01", "03"))   // the 02:00 file is missing
            Files.writeString(inbox.resolve("cdr_20260614" + hh + ".csv"), "ID,AMT,EVENT_DATE\nr,1,2020-04-03\n");

        CollectorProcessor.run(cfg);

        List<com.gamma.event.Event> gaps = events.recent(1000).stream()
                .filter(e -> com.gamma.event.EventType.SEQUENCE_GAP.equals(e.type()))
                .toList();
        assertEquals(1, gaps.size(), "exactly one hole reported (the 02:00 file)");
        assertEquals("cdr_2026061402.csv", String.valueOf(gaps.get(0).attributes().get("expected")));

        // the poll loop re-runs each cycle; a persistent gap must not re-fire
        CollectorProcessor.run(cfg);
        long after = events.recent(1000).stream()
                .filter(e -> com.gamma.event.EventType.SEQUENCE_GAP.equals(e.type())).count();
        assertEquals(1, after, "a persistent gap fires once, not every poll cycle");
    }
}
