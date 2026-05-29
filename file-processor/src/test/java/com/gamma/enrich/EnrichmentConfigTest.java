package com.gamma.enrich;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EnrichmentConfig#load} — the enrichment {@code .toon} parser.
 */
class EnrichmentConfigTest {

    @Test
    void parsesFullConfigIncludingReferencesMap(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("enrich.toon");
        Files.writeString(toon, """
                name: EVENTS_DAILY_KPI
                version: 1
                input:
                  database: database/events
                  format: PARQUET
                  partitions[4]: event_type, year, month, day
                references:
                  region_dim:
                    path: ref/region_dim.parquet
                    format: PARQUET
                  account_dim:
                    path: ref/account_dim.parquet
                    format: CSV
                output:
                  database: reports/events_daily
                  format: PARQUET
                  compression: snappy
                  partitions[2]: event_type, day
                transform: "SELECT event_type, day, COUNT(*) AS n FROM input GROUP BY event_type, day"
                """);

        EnrichmentConfig cfg = EnrichmentConfig.load(toon.toString());

        assertEquals("EVENTS_DAILY_KPI", cfg.name());
        assertEquals("database/events", cfg.input().database());
        assertEquals("PARQUET", cfg.input().format());
        assertEquals(java.util.List.of("event_type", "year", "month", "day"), cfg.input().partitions());

        assertEquals(2, cfg.references().size());
        var byName = new java.util.HashMap<String, EnrichmentConfig.Reference>();
        cfg.references().forEach(r -> byName.put(r.name(), r));
        assertEquals("ref/region_dim.parquet", byName.get("region_dim").path());
        assertEquals("PARQUET", byName.get("region_dim").format());
        assertEquals("CSV", byName.get("account_dim").format());

        assertEquals("reports/events_daily", cfg.output().database());
        assertEquals("snappy", cfg.output().compression());
        assertEquals(java.util.List.of("event_type", "day"), cfg.output().partitions());
        assertTrue(cfg.transformSql().startsWith("SELECT event_type, day"));
    }

    @Test
    void triggersAreParsedWhenPresent(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("trig.toon");
        Files.writeString(toon, """
                name: T
                version: 1
                input:
                  database: in
                  format: PARQUET
                  partitions[1]: day
                output:
                  database: out
                  format: PARQUET
                  partitions[1]: day
                triggers:
                  on_pipeline: EVENTS
                  schedule_seconds: 3600
                transform: "SELECT day, COUNT(*) AS n FROM input GROUP BY day"
                """);
        EnrichmentConfig cfg = EnrichmentConfig.load(toon.toString());
        assertTrue(cfg.triggers().hasEvent());
        assertEquals("EVENTS", cfg.triggers().onPipeline());
        assertTrue(cfg.triggers().hasSchedule());
        assertEquals(3600L, cfg.triggers().scheduleSeconds());
    }

    @Test
    void triggersDefaultToNoneWhenAbsent(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("notrig.toon");
        Files.writeString(toon, """
                name: T
                version: 1
                input:
                  database: in
                  format: PARQUET
                  partitions[1]: day
                output:
                  database: out
                  format: PARQUET
                  partitions[1]: day
                transform: "SELECT day, COUNT(*) AS n FROM input GROUP BY day"
                """);
        EnrichmentConfig cfg = EnrichmentConfig.load(toon.toString());
        assertFalse(cfg.triggers().hasEvent());
        assertFalse(cfg.triggers().hasSchedule());
    }

    @Test
    void referencesAreOptional(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("noref.toon");
        Files.writeString(toon, """
                name: T
                version: 1
                input:
                  database: in
                  format: PARQUET
                  partitions[1]: day
                output:
                  database: out
                  format: CSV
                  partitions[1]: day
                transform: "SELECT day, COUNT(*) AS n FROM input GROUP BY day"
                """);
        EnrichmentConfig cfg = EnrichmentConfig.load(toon.toString());
        assertTrue(cfg.references().isEmpty());
    }

    @Test
    void transformFileIsReadWhenInlineAbsent(@TempDir Path dir) throws Exception {
        Path sql = dir.resolve("t.sql");
        Files.writeString(sql, "SELECT day, COUNT(*) AS n FROM input GROUP BY day");
        Path toon = dir.resolve("tf.toon");
        Files.writeString(toon, """
                name: T
                version: 1
                input:
                  database: in
                  format: PARQUET
                  partitions[1]: day
                output:
                  database: out
                  format: PARQUET
                  partitions[1]: day
                transform_file: %s
                """.formatted(sql.toString().replace("\\", "/")));
        EnrichmentConfig cfg = EnrichmentConfig.load(toon.toString());
        assertEquals("SELECT day, COUNT(*) AS n FROM input GROUP BY day", cfg.transformSql());
    }

    @Test
    void missingTransformThrows(@TempDir Path dir) throws Exception {
        Path toon = dir.resolve("notransform.toon");
        Files.writeString(toon, """
                name: T
                version: 1
                input:
                  database: in
                  format: PARQUET
                  partitions[1]: day
                output:
                  database: out
                  format: PARQUET
                  partitions[1]: day
                """);
        assertThrows(IllegalArgumentException.class, () -> EnrichmentConfig.load(toon.toString()));
    }
}
