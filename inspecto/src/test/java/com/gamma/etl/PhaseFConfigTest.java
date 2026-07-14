package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Phase F config: additive {@code source.fetch / retry / circuit_breaker / post_action} parsing + defaults. */
class PhaseFConfigTest {

    private static Path writePipeline(Path dir, String sourceBlock) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String toon = """
            name: PF_ETL
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
        Path p = dir.resolve("pf_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @Test
    void defaultsWhenNoPhaseFBlocks(@TempDir Path dir) throws Exception {
        PipelineConfig.Collector s = PipelineConfig.load(writePipeline(dir, "").toString()).collector();
        assertEquals(1, s.fetch().parallelFetch());
        assertFalse(s.fetch().parallel());
        assertFalse(s.fetch().rateLimited());
        assertFalse(s.retry().enabled());
        assertFalse(s.circuitBreaker().enabled());
        assertFalse(s.postAction().active(), "RETAIN by default");
        assertEquals("RETAIN", s.postAction().onSuccess());
    }

    @Test
    void parsesAllPhaseFBlocks(@TempDir Path dir) throws Exception {
        String src = """
            collector:
              connector: local
              fetch:
                mode: STAGE
                parallel_fetch: 8
                rate_limit: 50MBps
              retry:
                count: 5
                backoff: EXPONENTIAL
                initial_delay: 30s
                max_delay: 15m
              circuit_breaker:
                failure_threshold: 4
                cooldown: 5m
              post_action:
                on_success: MOVE
                archive_path: archive/yyyy/MM/dd
                on_unsupported: WARN_AND_CONTINUE
            """;
        PipelineConfig.Collector s = PipelineConfig.load(writePipeline(dir, src).toString()).collector();

        assertEquals(8, s.fetch().parallelFetch());
        assertTrue(s.fetch().parallel());
        assertEquals(50L * (1L << 20), s.fetch().rateLimitBytesPerSec());
        assertTrue(s.fetch().rateLimited());

        assertTrue(s.retry().enabled());
        assertEquals(5, s.retry().count());
        assertEquals(30_000L, s.retry().initialDelayMillis());
        assertEquals(15 * 60_000L, s.retry().maxDelayMillis());

        assertTrue(s.circuitBreaker().enabled());
        assertEquals(4, s.circuitBreaker().failureThreshold());
        assertEquals(5 * 60_000L, s.circuitBreaker().cooldownMillis());

        assertTrue(s.postAction().active());
        assertEquals("MOVE", s.postAction().onSuccess());
        assertEquals("archive/yyyy/MM/dd", s.postAction().archivePath());
        assertEquals("WARN_AND_CONTINUE", s.postAction().onUnsupported());
    }

    @Test
    void rateParserHandlesUnitsAndSuffixes() {
        assertEquals(0L, PipelineConfigParser.parseRate(null));
        assertEquals(0L, PipelineConfigParser.parseRate(""));
        assertEquals(1L << 20, PipelineConfigParser.parseRate("1MBps"));
        assertEquals(1L << 20, PipelineConfigParser.parseRate("1MB/s"));
        assertEquals(50L * (1L << 20), PipelineConfigParser.parseRate("50MB"));
        assertEquals(512L * (1L << 10), PipelineConfigParser.parseRate("512KBps"));
        assertEquals(2L * (1L << 30), PipelineConfigParser.parseRate("2GBps"));
        assertEquals(1024L, PipelineConfigParser.parseRate("1024"));
    }

    @Test
    void postActionResolvesArchiveDateTemplate() {
        java.time.ZonedDateTime when = java.time.ZonedDateTime.of(2026, 6, 14, 9, 5, 3, 0,
                java.time.ZoneOffset.UTC);
        assertEquals("archive/2026/06/14",
                com.gamma.acquire.PostAction.resolveTemplate("archive/yyyy/MM/dd", when));
        assertEquals("a/2026/06/14/09",
                com.gamma.acquire.PostAction.resolveTemplate("a/yyyy/MM/dd/HH", when));
        assertNull(com.gamma.acquire.PostAction.resolveTemplate(null, when));
    }
}
