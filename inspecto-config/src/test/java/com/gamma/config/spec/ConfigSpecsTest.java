package com.gamma.config.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the declarative config specs (P0): every authored {@link ConfigSpec} resolves, the
 * record types enforce their null-safety / defensive-copy contracts, and each {@link CrossFieldRule}
 * fires (or stays silent) exactly where the imperative loaders/{@code ConfigValidator} would —
 * proving the spec and the existing code agree.
 */
class ConfigSpecsTest {

    // ── spec resolution ──────────────────────────────────────────────────────────

    @Test
    void forTypeResolvesEveryKnownTypeAndRejectsUnknown() {
        for (String t : ConfigSpecs.TYPES) {
            ConfigSpec spec = ConfigSpecs.forType(t);
            assertNotNull(spec, "spec for " + t);
            assertEquals(t, spec.type());
            assertFalse(spec.fields().isEmpty(), t + " should declare fields");
        }
        assertNull(ConfigSpecs.forType("nope"));
        assertNull(ConfigSpecs.forType(null));
    }

    @Test
    void forTypeIsCaseInsensitive() {
        assertNotNull(ConfigSpecs.forType("PIPELINE"));
        assertEquals("pipeline", ConfigSpecs.forType("Pipeline").type());
    }

    // ── record contracts ──────────────────────────────────────────────────────────

    @Test
    void fieldSpecNormalisesNullsAndCopiesEnumValues() {
        FieldSpec f = new FieldSpec(null, null, null, null, false, null, null, null, null, null);
        assertEquals("", f.path());
        assertEquals(FieldType.STRING, f.type());
        assertNotNull(f.enumValues());
        assertTrue(f.enumValues().isEmpty());

        FieldSpec e = FieldSpec.enumField("p", "L", List.of("a", "b"), "a", "d");
        assertThrows(UnsupportedOperationException.class, () -> e.enumValues().add("c"));
    }

    @Test
    void configSpecFieldLookupWorks() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Optional<FieldSpec> threads = p.field("processing.threads");
        assertTrue(threads.isPresent());
        assertEquals(4, threads.get().defaultValue());
        assertTrue(p.field("does.not.exist").isEmpty());
    }

    @Test
    void crossFieldRuleCheckReportsFindingOnViolationOnly() {
        CrossFieldRule rule = new CrossFieldRule("r", "must hold", Severity.ERROR,
                List.of("a.b"), raw -> RawConfig.present(raw, "a.b"));
        assertTrue(rule.check(Map.of("a", Map.of("b", "x"))).isEmpty(), "satisfied → no finding");

        Optional<Finding> f = rule.check(Map.of());
        assertTrue(f.isPresent());
        assertEquals(Severity.ERROR, f.get().severity());
        assertEquals("a.b", f.get().fieldPath());
        assertEquals("must hold", f.get().message());
    }

    // ── pipeline cross-field rules (mirror PipelineConfig.load + ConfigValidator) ──

    private Optional<Finding> fire(ConfigSpec spec, String ruleId, Map<String, Object> raw) {
        CrossFieldRule rule = spec.rules().stream().filter(r -> r.id().equals(ruleId)).findFirst()
                .orElseThrow(() -> new AssertionError("no rule " + ruleId));
        return rule.check(raw);
    }

    @Test
    void pluginIngesterRequiresNonEmptySegments() {
        ConfigSpec p = ConfigSpecs.pipeline();
        // ingester set, segments missing → ERROR (matches PipelineConfig.load throw)
        Map<String, Object> bad = Map.of("processing", Map.of("ingester", "com.x.Plugin"));
        assertTrue(fire(p, "plugin-ingester-requires-segments", bad).isPresent());

        // ingester set, segments present → ok
        Map<String, Object> good = Map.of("processing",
                Map.of("ingester", "com.x.Plugin", "segments", Map.of("CALL", "call_schema.toon")));
        assertTrue(fire(p, "plugin-ingester-requires-segments", good).isEmpty());

        // no ingester → ok regardless of segments
        assertTrue(fire(p, "plugin-ingester-requires-segments", Map.of("processing", Map.of())).isEmpty());
    }

    @Test
    void threadsTimesDuckdbOversubscriptionWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        int cores = Runtime.getRuntime().availableProcessors();
        Map<String, Object> over = Map.of("processing",
                Map.of("threads", cores + 1, "duckdb_threads", 2));
        assertEquals(Severity.WARNING,
                fire(p, "threads-x-duckdb-threads-oversubscription", over).orElseThrow().severity());

        // duckdb_threads=0 (default) → never warns
        Map<String, Object> off = Map.of("processing", Map.of("threads", 999, "duckdb_threads", 0));
        assertTrue(fire(p, "threads-x-duckdb-threads-oversubscription", off).isEmpty());
    }

    @Test
    void duckdbEngineWithSkipTailColumnsWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Map<String, Object> bad = Map.of("processing",
                Map.of("csv_settings", Map.of("engine", "duckdb", "skip_tail_columns", 1)));
        assertTrue(fire(p, "duckdb-engine-x-skip-tail-columns", bad).isPresent());

        Map<String, Object> javaEngine = Map.of("processing",
                Map.of("csv_settings", Map.of("engine", "java", "skip_tail_columns", 3)));
        assertTrue(fire(p, "duckdb-engine-x-skip-tail-columns", javaEngine).isEmpty());
    }

    @Test
    void threadsVsBatchMaxFilesWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Map<String, Object> bad = Map.of("processing",
                Map.of("threads", 4, "batch", Map.of("max_files", 1)));
        assertTrue(fire(p, "threads-vs-batch-max-files", bad).isPresent());

        Map<String, Object> ok = Map.of("processing",
                Map.of("threads", 4, "batch", Map.of("max_files", 8)));
        assertTrue(fire(p, "threads-vs-batch-max-files", ok).isEmpty());
    }

    @Test
    void duplicateCheckRetentionWarns() {
        ConfigSpec p = ConfigSpecs.pipeline();
        Map<String, Object> bad = Map.of("processing",
                Map.of("duplicate_check", Map.of("enabled", true, "retention_days", 0)));
        assertTrue(fire(p, "duplicate-check-retention", bad).isPresent());

        Map<String, Object> ok = Map.of("processing",
                Map.of("duplicate_check", Map.of("enabled", true, "retention_days", 30)));
        assertTrue(fire(p, "duplicate-check-retention", ok).isEmpty());

        // disabled → never warns even with retention 0
        Map<String, Object> disabled = Map.of("processing",
                Map.of("duplicate_check", Map.of("enabled", false, "retention_days", 0)));
        assertTrue(fire(p, "duplicate-check-retention", disabled).isEmpty());
    }

    // ── enrichment + job rules ──────────────────────────────────────────────────

    @Test
    void enrichmentRequiresTransformOrFile() {
        ConfigSpec e = ConfigSpecs.enrichment();
        assertTrue(fire(e, "transform-or-transform-file", Map.of()).isPresent());
        assertTrue(fire(e, "transform-or-transform-file", Map.of("transform", "SELECT 1")).isEmpty());
        assertTrue(fire(e, "transform-or-transform-file",
                Map.of("transform_file", "x.sql")).isEmpty());
    }

    @Test
    void jobTypeAndCronRules() {
        ConfigSpec j = ConfigSpecs.job();
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of("type", "enrich"))).isEmpty());
        // ingest is no longer a job type (T23 / §3.8 — ingest is pipeline-exclusive)
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of("type", "ingest"))).isPresent());
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of("type", "bogus"))).isPresent());
        assertTrue(fire(j, "job-type-required", Map.of("job", Map.of())).isPresent());

        // absent cron → ok; 5 fields → ok; 6 fields → ok; 3 fields → error
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of())).isEmpty());
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of("cron", "0 2 * * *"))).isEmpty());
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of("cron", "0 0 2 * * *"))).isEmpty());
        assertTrue(fire(j, "cron-field-count", Map.of("job", Map.of("cron", "0 2 *"))).isPresent());
    }
}
