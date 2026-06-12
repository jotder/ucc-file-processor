package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ControlApi#schemaFileFindings}: the pre-flight check that a pipeline draft's
 * {@code schema_file} reference(s) resolve on this server before save/registration (v4.1.0).
 */
class SchemaFileFindingsTest {

    @Test
    void missingLegacySchemaFileIsFlaggedAtTheRequestedSeverity() {
        Map<String, Object> draft = Map.of("name", "X",
                "processing", Map.of("schema_file", "no/such/dir/ghost_schema.toon"));

        List<Finding> warn = ControlApi.schemaFileFindings("pipeline", draft, Severity.WARNING);
        assertEquals(1, warn.size());
        assertEquals(Severity.WARNING, warn.get(0).severity());
        assertEquals("processing.schema_file", warn.get(0).fieldPath());
        assertTrue(warn.get(0).message().contains("ghost_schema.toon"));

        assertEquals(Severity.ERROR,
                ControlApi.schemaFileFindings("pipeline", draft, Severity.ERROR).get(0).severity());
    }

    @Test
    void resolvableSchemaFileIsClean(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("ok_schema.toon");
        Files.writeString(schema, "raw:\n  name: ok\n");
        Map<String, Object> draft = Map.of("name", "X",
                "processing", Map.of("schema_file", schema.toString()));
        assertTrue(ControlApi.schemaFileFindings("pipeline", draft, Severity.ERROR).isEmpty());
    }

    @Test
    void multiSchemaEntriesAreCheckedIndividually(@TempDir Path dir) throws Exception {
        Path ok = dir.resolve("ok_schema.toon");
        Files.writeString(ok, "raw:\n  name: ok\n");
        Map<String, Object> draft = Map.of("name", "X", "processing", Map.of("schemas", List.of(
                Map.of("schema_file", ok.toString(), "column_count", 3),
                Map.of("schema_file", dir.resolve("ghost.toon").toString(), "column_count", 5))));

        List<Finding> f = ControlApi.schemaFileFindings("pipeline", draft, Severity.ERROR);
        assertEquals(1, f.size(), "only the unresolvable entry is flagged");
        assertEquals("processing.schemas[1].schema_file", f.get(0).fieldPath());
    }

    @Test
    void nonPipelineTypesAndAbsentOrBlankReferencesAreNoOps() {
        assertTrue(ControlApi.schemaFileFindings("job",
                Map.of("processing", Map.of("schema_file", "ghost.toon")), Severity.ERROR).isEmpty());
        assertTrue(ControlApi.schemaFileFindings("pipeline", Map.of("name", "X"), Severity.ERROR).isEmpty());
        assertTrue(ControlApi.schemaFileFindings("pipeline",
                Map.of("processing", Map.of("schema_file", " ")), Severity.ERROR).isEmpty());
    }
}
