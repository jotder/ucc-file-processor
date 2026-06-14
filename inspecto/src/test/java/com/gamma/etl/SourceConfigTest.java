package com.gamma.etl;

import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.acquire.LocalFileSystemConnector;
import com.gamma.acquire.SourceConnector;
import com.gamma.acquire.SourceConnectors;
import com.gamma.inspector.SourceProcessor;
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
        PipelineConfig.Source s = cfg.source();
        assertEquals("src_etl", s.id());
        assertEquals("local", s.connector());
        assertEquals(List.of("glob:**/*.csv"), s.includes(), "includes default to processing.file_pattern");
        assertTrue(s.excludes().isEmpty());
        assertEquals(-1, s.recursiveDepth(), "unbounded by default");
        try (SourceConnector c = SourceConnectors.forConfig(cfg)) {
            assertInstanceOf(LocalFileSystemConnector.class, c);
        }
    }

    @Test
    void sourceBlockOverridesDiscovery(@TempDir Path dir) throws Exception {
        String block = """
            source:
              id: CDR_LOCAL
              connector: local
              include[1]: "glob:**/*.dat"
              exclude[2]: "*.tmp", "*.partial"
              recursive_depth: 2
            """;
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, block).toString());
        PipelineConfig.Source s = cfg.source();
        assertEquals("CDR_LOCAL", s.id());
        assertEquals("local", s.connector());
        assertEquals(List.of("glob:**/*.dat"), s.includes());
        assertEquals(List.of("*.tmp", "*.partial"), s.excludes());
        assertEquals(2, s.recursiveDepth());
    }

    @Test
    void unknownConnectorFailsFastWithClearMessage(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            source:
              connector: sftp
            """).toString());
        assertEquals("sftp", cfg.source().connector());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> SourceConnectors.forConfig(cfg));
        assertTrue(ex.getMessage().contains("sftp"), ex.getMessage());
    }

    @Test
    void sourceConnectionBindingParses(@TempDir Path dir) throws Exception {
        PipelineConfig none = PipelineConfig.load(writePipeline(dir, "").toString());
        assertNull(none.source().connection(), "no source block ⇒ no connection binding");
        assertFalse(none.source().hasConnection());

        PipelineConfig bound = PipelineConfig.load(writePipeline(dir, """
            source:
              connector: sftp
              connection: CDR_SFTP_PROD
            """).toString());
        assertEquals("CDR_SFTP_PROD", bound.source().connection());
        assertTrue(bound.source().hasConnection());
    }

    @Test
    void metadataDedupSkipsUnchangedAndReprocessesChanged(@TempDir Path dir) throws Exception {
        InMemoryAcquisitionLedger ledger = new InMemoryAcquisitionLedger();
        AcquisitionLedgers.use(ledger);
        try {
            PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
                source:
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
            assertTrue(SourceProcessor.collectCandidates(cfg).isEmpty(), "unchanged file is a duplicate");

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
                source:
                  id: RUN_SRC
                  duplicate:
                    mode: METADATA
                """).toString());
            Path inbox = Path.of(cfg.dirs().poll());
            Files.createDirectories(inbox);
            Files.writeString(inbox.resolve("a.csv"), "ID,AMT,EVENT_DATE\nr,1,2020-04-03\n");

            SourceProcessor.run(cfg);   // a full ingest cycle

            assertTrue(ledger.find("RUN_SRC", "a.csv").isPresent(),
                    "the fingerprint is recorded once the batch commits");
        } finally {
            AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        }
    }

    private static List<String> candidateNames(PipelineConfig cfg) throws Exception {
        return SourceProcessor.collectCandidates(cfg).stream().map(File::getName).sorted().toList();
    }

    @Test
    void duplicateBlockParsesModeAlgorithmAndOnChange(@TempDir Path dir) throws Exception {
        PipelineConfig none = PipelineConfig.load(writePipeline(dir, "").toString());
        assertEquals("path", none.source().duplicate().mode(), "absent block ⇒ path (legacy)");
        assertFalse(none.source().duplicate().contentBased());

        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            source:
              duplicate:
                mode: CHECKSUM
                algorithm: SHA256
                on_change: REPROCESS
            """).toString());
        PipelineConfig.Duplicate d = cfg.source().duplicate();
        assertEquals("checksum", d.mode());
        assertEquals("SHA256", d.algorithm());
        assertEquals("reprocess", d.onChange());
        assertTrue(d.contentBased());
    }

    @Test
    void noStabilityBlockIsDisabled(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, "").toString());
        assertFalse(cfg.source().stability().enabled());
        assertEquals(PipelineConfig.Stability.DISABLED, cfg.source().stability());
    }

    @Test
    void stabilityBlockParsesWindowChecksMarkerAndTempExclusion(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            source:
              stability:
                window: 5m
                size_checks: 3
                ready_marker: "{name}.ok"
                exclude_temp_files: true
            """).toString());
        PipelineConfig.Stability st = cfg.source().stability();
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
            source:
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

        List<String> names = SourceProcessor.collectCandidates(cfg).stream()
                .map(File::getName).sorted().toList();
        assertEquals(List.of("data.csv"), names,
                "only the marked file is ready; pending is held, the .tmp is excluded, the .done sentinel is not a candidate");
    }

    @Test
    void collectCandidatesHonoursExcludeAndRecursiveDepth(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(writePipeline(dir, """
            source:
              include[1]: "glob:**/*"
              exclude[1]: "*.tmp"
              recursive_depth: 1
            """).toString());

        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox.resolve("sub"));
        Files.writeString(inbox.resolve("top.csv"), "x");          // depth 1, included
        Files.writeString(inbox.resolve("skip.tmp"), "x");         // depth 1, excluded by *.tmp
        Files.writeString(inbox.resolve("sub/deep.csv"), "x");     // depth 2, excluded by recursive_depth:1

        List<String> names = SourceProcessor.collectCandidates(cfg).stream()
                .map(File::getName).sorted().toList();
        assertEquals(List.of("top.csv"), names);
    }
}
