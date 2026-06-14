package com.gamma.etl;

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
