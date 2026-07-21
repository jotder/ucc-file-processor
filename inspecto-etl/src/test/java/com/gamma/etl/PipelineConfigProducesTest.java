package com.gamma.etl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code produces:} top-level key (v5.1.0): absent ⇒ STREAM (the prior behaviour),
 * {@code reference} marks the output as a catalog Reference Dataset, anything else is rejected
 * at parse — the same contract the {@code ConfigSpecs.pipeline()} enum field enforces at
 * {@code /config/write}.
 */
class PipelineConfigProducesTest {

    private static Map<String, Object> minimal(String produces) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "MINI");
        if (produces != null) m.put("produces", produces);
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        return m;
    }

    @Test
    void absentProducesDefaultsToStream() throws Exception {
        PipelineConfig cfg = PipelineConfig.fromMap(minimal(null));
        assertEquals(PipelineConfig.Produces.STREAM, cfg.produces());
        assertFalse(cfg.producesReference());
    }

    @Test
    void referenceIsParsedCaseInsensitively() throws Exception {
        assertTrue(PipelineConfig.fromMap(minimal("reference")).producesReference());
        assertTrue(PipelineConfig.fromMap(minimal("REFERENCE")).producesReference());
        assertEquals(PipelineConfig.Produces.STREAM,
                PipelineConfig.fromMap(minimal("stream")).produces());
    }

    @Test
    void unknownProducesValueIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.fromMap(minimal("refrence")));
        assertTrue(ex.getMessage().contains("produces"), ex.getMessage());
    }
}
