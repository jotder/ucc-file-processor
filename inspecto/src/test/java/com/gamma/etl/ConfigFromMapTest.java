package com.gamma.etl;

import com.gamma.enrich.EnrichmentConfig;
import com.gamma.job.JobConfig;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the P2 parse/prepare split: {@code fromMap} is a pure parse (no directory creation), the
 * side-effect lives only in {@code prepare()}, and {@code load} (= fromMap + prepare) is unchanged.
 * The pre-existing {@code PipelineConfigBatchTest}/{@code EnrichmentConfigTest}/{@code JobConfigTest}
 * remain the regression proof that {@code load}'s observable behaviour is intact; this class proves
 * the new pure entry points exist and behave.
 */
class ConfigFromMapTest {

    // ── PipelineConfig ────────────────────────────────────────────────────────────

    @Test
    void fromMapParsesButDoesNotCreateStatusDir(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        Path statusDir = dir.resolve("status");
        assertFalse(Files.exists(statusDir), "precondition: status dir not yet created");

        PipelineConfig cfg = PipelineConfig.fromMap(ToonHelper.load(p.toString()));

        // pure parse: paths derived, but no filesystem mutation
        assertEquals("mini_etl", cfg.identity().pipelineName());
        assertNotNull(cfg.dirs().statusFilePath());
        assertTrue(cfg.dirs().statusFilePath().contains("_status_"));
        assertNotNull(cfg.dirs().batchesFilePath());
        assertFalse(Files.exists(statusDir), "fromMap must NOT create the status dir");

        // the one side-effect happens only on prepare()
        cfg.prepare();
        assertTrue(Files.exists(statusDir), "prepare() creates the status dir");
        assertDoesNotThrow(cfg::prepare); // idempotent
    }

    @Test
    void loadStillCreatesStatusDir(@TempDir Path dir) throws Exception {
        Path p = PipelineConfigBatchTest.writePipeline(dir, "");
        PipelineConfig.load(p.toString());
        assertTrue(Files.exists(dir.resolve("status")), "load (= fromMap + prepare) creates the status dir");
    }

    // ── EnrichmentConfig ────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void enrichmentFromMapUsesResolvedSqlOrInlineTransform() {
        Map<String, Object> raw = (Map<String, Object>) JToon.decode("""
                name: K
                input:
                  database: db/in
                  format: PARQUET
                  partitions[1]: day
                output:
                  database: db/out
                  format: PARQUET
                  partitions[1]: day
                """);
        // resolved SQL (as if read from a transform_file) wins
        EnrichmentConfig a = EnrichmentConfig.fromMap(raw, "SELECT 1 FROM input");
        assertEquals("SELECT 1 FROM input", a.transformSql());

        // null resolved SQL with no inline transform → the same error load would raise
        assertThrows(IllegalArgumentException.class, () -> EnrichmentConfig.fromMap(raw, null));
    }

    // ── JobConfig ──────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void jobFromMapParsesAndValidatesCron() {
        Map<String, Object> ok = (Map<String, Object>) JToon.decode("""
                job:
                  name: nightly
                  type: enrich
                  cron: "0 2 * * *"
                  config: x.toon
                """);
        JobConfig j = JobConfig.fromMap(ok);
        assertEquals("nightly", j.name());
        assertTrue(j.hasCron());
        assertEquals("x.toon", j.params().get("config"));

        Map<String, Object> badCron = (Map<String, Object>) JToon.decode("""
                job:
                  name: bad
                  type: enrich
                  cron: "not a cron"
                """);
        assertThrows(RuntimeException.class, () -> JobConfig.fromMap(badCron));
    }
}
