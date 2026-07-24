package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Reference Phase-2 (P0) config model: the {@code reference:} block
 * ({@code load}/{@code key}/{@code refresh_seconds}) and the {@code stream:} membership key.
 *
 * <p>All additive and backward-compatible: an existing pipeline with no {@code reference:} block
 * parses to {@link PipelineConfig.Load#REPLACE} (today's full-replace behaviour) and a {@code stream}
 * equal to its own name (strict 1:1). Mirrors the declarative contract that
 * {@code ConfigSpecs.pipeline()} + the {@code reference-upsert-requires-key} rule enforce at
 * {@code /config/write}.
 */
class PipelineConfigReferenceTest {

    /** A minimal in-memory {@code produces: reference} pipeline draft (no schema ⇒ column check skipped). */
    private static Map<String, Object> minimal(Map<String, Object> reference) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "REGION DIM");                 // note the space → normalises to region_dim
        m.put("produces", "reference");
        if (reference != null) m.put("reference", reference);
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        return m;
    }

    // ── load modes ─────────────────────────────────────────────────────────────────

    @Test
    void absentReferenceBlockDefaultsToReplace() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(null));
        assertEquals(PipelineConfig.Load.REPLACE, cfg.reference().load());
        assertTrue(cfg.reference().key().isEmpty());
        assertEquals(0, cfg.reference().refreshSeconds());
        assertFalse(cfg.reference().refreshEnabled());
    }

    @Test
    void explicitReplaceStaysReplace() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(Map.of("load", "replace")));
        assertEquals(PipelineConfig.Load.REPLACE, cfg.reference().load());
    }

    @Test
    void upsertWithKeyParsesModeAndKey() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(
                minimal(Map.of("load", "upsert", "key", List.of("customer_id"))));
        assertEquals(PipelineConfig.Load.UPSERT, cfg.reference().load());
        assertEquals(List.of("customer_id"), cfg.reference().key());
        assertTrue(cfg.reference().load().requiresKey());
    }

    @Test
    void scd2WithKeyAndRefreshTimer() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(
                Map.of("load", "scd2", "key", List.of("cell_id", "valid_from"), "refresh_seconds", 3600)));
        assertEquals(PipelineConfig.Load.SCD2, cfg.reference().load());
        assertEquals(List.of("cell_id", "valid_from"), cfg.reference().key());
        assertEquals(3600, cfg.reference().refreshSeconds());
        assertTrue(cfg.reference().refreshEnabled());
    }

    @Test
    void loadIsCaseInsensitive() throws Exception {
        assertEquals(PipelineConfig.Load.UPSERT,
                PipelineConfig.fromMap(minimal(Map.of("load", "UPSERT", "key", List.of("id")))).reference().load());
    }

    // ── validation (fail closed) ─────────────────────────────────────────────────────

    @Test
    void upsertWithoutKeyIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(Map.of("load", "upsert"))));
        assertTrue(ex.getMessage().contains("key"), ex.getMessage());
    }

    @Test
    void scd2WithoutKeyIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(Map.of("load", "scd2"))));
        assertTrue(ex.getMessage().contains("key"), ex.getMessage());
    }

    @Test
    void unknownLoadValueIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal(Map.of("load", "merge"))));
        assertTrue(ex.getMessage().contains("load"), ex.getMessage());
    }

    @Test
    void negativeRefreshSecondsClampsToZero() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(Map.of("load", "replace", "refresh_seconds", -5)));
        assertEquals(0, cfg.reference().refreshSeconds());
    }

    // ── stream: membership key ───────────────────────────────────────────────────────

    @Test
    void streamDefaultsToPipelineName() throws Exception {
        // no stream: → 1:1 with the pipeline (name "REGION DIM" normalises to region_dim)
        assertEquals("region_dim", PipelineConfig.fromMap(minimal(null)).stream());
    }

    @Test
    void explicitStreamIsNormalisedAndUsed() throws Exception {
        Map<String, Object> m = minimal(null);
        m.put("stream", "Mediation Group");
        assertEquals("mediation_group", PipelineConfig.fromMap(m).stream());
    }

    @Test
    void invalidStreamIdentifierIsRejected() {
        Map<String, Object> m = minimal(null);
        m.put("stream", "bad-stream");   // hyphen → not a SQL identifier
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(m));
        assertTrue(ex.getMessage().contains("stream"), ex.getMessage());
    }

    // ── reference.key must exist in the resolved schema (only when a schema is present) ──

    /** Write a {@code produces: reference} pipeline with a 3-column schema and the given {@code reference:} block. */
    private static PipelineConfig loadWithSchema(Path dir, String referenceBlock) throws Exception {
        Path schema = dir.resolve("dim_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());   // columns: ID, AMT, EVENT_DATE
        String toon = """
                name: REGION_DIM
                produces: reference
                %s
                dirs:
                  poll: %s/inbox
                  database: %s/db
                output:
                  format: PARQUET
                processing:
                  threads: 1
                  schema_file: "%s"
                """.formatted(referenceBlock,
                dir.toString().replace("\\", "/"), dir.toString().replace("\\", "/"),
                schema.toString().replace("\\", "/"));
        Path p = dir.resolve("dim_pipeline.toon");
        Files.writeString(p, toon);
        return PipelineConfig.load(p.toString());
    }

    @Test
    void keyColumnPresentInSchemaParses(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = loadWithSchema(dir, """
                reference:
                  load: upsert
                  key[1]: ID
                """);
        assertEquals(PipelineConfig.Load.UPSERT, cfg.reference().load());
        assertEquals(List.of("ID"), cfg.reference().key());
    }

    @Test
    void keyColumnMissingFromSchemaIsRejected(@TempDir Path dir) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> loadWithSchema(dir, """
                        reference:
                          load: upsert
                          key[1]: NOPE
                        """));
        assertTrue(ex.getMessage().contains("NOPE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("not declared"), ex.getMessage());
    }
}
