package com.gamma.config.io;

import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pure decode→validate pipeline (P1): decoding a draft from memory (no temp files),
 * and validating drafts against the authored specs — per-field required/type/enum checks plus the
 * cross-field rules — exactly as a REST {@code /validate} body or a UI form would.
 */
class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader(null); // null → filesystem default; unused here

    private static List<Finding> errors(List<Finding> findings) {
        return findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
    }

    private static boolean hasFindingAt(List<Finding> findings, String path) {
        return findings.stream().anyMatch(f -> f.fieldPath().equals(path));
    }

    // ── decode (in-memory draft, no filesystem) ───────────────────────────────────

    @Test
    void decodeReadsDraftFromMemory() throws Exception {
        ConfigLoader mem = new ConfigLoader(ResourceLoader.ofDraft(
                "name: ACME\ndirs:\n  poll: /in\n  database: /out\n"));
        Map<String, Object> raw = mem.decode(ResourceLoader.DRAFT);
        assertEquals("ACME", raw.get("name"));
        assertEquals("/in", ((Map<?, ?>) raw.get("dirs")).get("poll"));
    }

    // ── per-field validation ──────────────────────────────────────────────────────

    @Test
    void cleanPipelineDraftHasNoErrors() {
        ConfigSpec spec = ConfigSpecs.pipeline();
        Map<String, Object> raw = Map.of(
                "name", "ACME",
                "dirs", Map.of("poll", "/in", "database", "/out"),
                "processing", Map.of("threads", 1)); // threads=1 avoids the threads-vs-batch warning
        assertTrue(errors(loader.validate(spec, raw)).isEmpty(),
                "a minimal valid pipeline draft should produce no ERROR findings");
    }

    @Test
    void missingRequiredFieldIsReportedWithPath() {
        ConfigSpec spec = ConfigSpecs.pipeline();
        Map<String, Object> raw = Map.of("name", "ACME",
                "dirs", Map.of("poll", "/in")); // no database
        List<Finding> errs = errors(loader.validate(spec, raw));
        assertTrue(hasFindingAt(errs, "dirs.database"), "missing dirs.database should error");
    }

    @Test
    void badEnumAndBadIntAreReported() {
        ConfigSpec spec = ConfigSpecs.pipeline();
        Map<String, Object> raw = Map.of(
                "name", "ACME",
                "dirs", Map.of("poll", "/in", "database", "/out"),
                "output", Map.of("format", "XML"),                 // not in {CSV,PARQUET}
                "processing", Map.of("threads", "lots"));          // not an int
        List<Finding> errs = errors(loader.validate(spec, raw));
        assertTrue(hasFindingAt(errs, "output.format"));
        assertTrue(hasFindingAt(errs, "processing.threads"));
    }

    @Test
    void enumIsCaseInsensitive() {
        ConfigSpec spec = ConfigSpecs.pipeline();
        Map<String, Object> raw = Map.of(
                "name", "ACME",
                "dirs", Map.of("poll", "/in", "database", "/out"),
                "output", Map.of("format", "parquet"),             // lowercase accepted
                "processing", Map.of("threads", 1));
        assertTrue(errors(loader.validate(spec, raw)).isEmpty());
    }

    // ── cross-field validation surfaces through the loader ───────────────────────

    @Test
    void crossFieldRuleSurfacesAsErrorFinding() {
        ConfigSpec spec = ConfigSpecs.pipeline();
        Map<String, Object> raw = Map.of(
                "name", "ACME",
                "dirs", Map.of("poll", "/in", "database", "/out"),
                "processing", Map.of("ingester", "com.x.Plugin", "threads", 1)); // ingester w/o segments
        List<Finding> errs = errors(loader.validate(spec, raw));
        assertTrue(errs.stream().anyMatch(f -> f.message().contains("segments")),
                "plugin-ingester-requires-segments should surface as an ERROR");
    }

    @Test
    void referenceLoadEnumAndKeyRuleSurfaceThroughLoader() {
        ConfigSpec spec = ConfigSpecs.pipeline();
        Map<String, Object> base = Map.of("name", "REGION_DIM",
                "dirs", Map.of("poll", "/in", "database", "/out"),
                "processing", Map.of("threads", 1));

        // bad load enum → per-field ERROR anchored at reference.load
        Map<String, Object> badEnum = new java.util.HashMap<>(base);
        badEnum.put("reference", Map.of("load", "merge"));
        assertTrue(hasFindingAt(errors(loader.validate(spec, badEnum)), "reference.load"));

        // upsert without key → cross-field ERROR
        Map<String, Object> noKey = new java.util.HashMap<>(base);
        noKey.put("reference", Map.of("load", "upsert"));
        assertTrue(errors(loader.validate(spec, noKey)).stream()
                .anyMatch(f -> f.message().contains("reference.key")));

        // upsert with key → clean
        Map<String, Object> ok = new java.util.HashMap<>(base);
        ok.put("reference", Map.of("load", "upsert", "key", List.of("customer_id")));
        assertTrue(errors(loader.validate(spec, ok)).isEmpty());
    }

    @Test
    void enrichmentDraftValidates() {
        ConfigSpec spec = ConfigSpecs.enrichment();
        Map<String, Object> missing = Map.of(
                "name", "K",
                "input", Map.of("database", "db/in"),
                "output", Map.of("database", "db/out")); // no transform / transform_file
        assertFalse(errors(loader.validate(spec, missing)).isEmpty());

        Map<String, Object> ok = Map.of(
                "name", "K",
                "input", Map.of("database", "db/in"),
                "output", Map.of("database", "db/out"),
                "transform", "SELECT 1");
        assertTrue(errors(loader.validate(spec, ok)).isEmpty());
    }

    @Test
    void nullSpecOrMapYieldsNoFindings() {
        assertTrue(loader.validate(null, Map.of()).isEmpty());
        assertTrue(loader.validate(ConfigSpecs.job(), null).isEmpty());
    }
}
