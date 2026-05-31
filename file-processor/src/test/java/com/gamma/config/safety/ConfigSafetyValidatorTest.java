package com.gamma.config.safety;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Adversarial tests for the hard-fail config safety gate (R6). This is the security core of M5, so
 * the cases are the attacks: path traversal, UNC, escape-the-workspace, symlink escape, and
 * out-of-bounds numerics / unknown output sinks. A clean draft under-root must pass with no findings.
 */
class ConfigSafetyValidatorTest {

    /** A minimal pipeline map with the given dirs map + optional processing/output overlays. */
    private static Map<String, Object> pipeline(Map<String, Object> dirs) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "TEST");
        raw.put("dirs", dirs);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "PARQUET");
        raw.put("output", output);
        return raw;
    }

    private static Map<String, Object> safeDirs(Path root) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("poll", root.resolve("inbox").toString());
        d.put("database", root.resolve("db").toString());
        d.put("backup", root.resolve("backup").toString());
        return d;
    }

    private static boolean hasError(List<Finding> findings, String fieldContains) {
        return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR
                && f.fieldPath().contains(fieldContains));
    }

    @Test
    void cleanDraftUnderRootPasses(@TempDir Path root) {
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(safeDirs(root)),
                SafetyPolicy.withRoots(root));
        assertTrue(f.isEmpty(), "a draft fully under the allowed root is safe: " + f);
    }

    @Test
    void dotDotEscapeIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("database", root.resolve("sub").resolve("..").resolve("..").resolve("escape").toString());
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.database"), "`..` escape must be rejected: " + f);
    }

    @Test
    void absolutePathOutsideRootIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("database", Path.of("/etc/secret-db").toAbsolutePath().toString());
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.database"), "an absolute path outside the root must be rejected: " + f);
    }

    @Test
    void uncPathIsRejected(@TempDir Path root) {
        Map<String, Object> dirs = safeDirs(root);
        dirs.put("backup", "\\\\fileserver\\share\\exfil");
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.backup"), "UNC/network path must be rejected: " + f);
    }

    @Test
    void symlinkEscapeIsRejected(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path link = root.resolve("sneaky");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "OS cannot create symlinks here; skipping symlink-escape case");
        }
        Map<String, Object> dirs = safeDirs(root);
        // Normalised path is under root (root/sneaky/db), but the real path resolves into `outside`.
        dirs.put("database", link.resolve("db").toString());
        List<Finding> f = ConfigSafetyValidator.check("pipeline", pipeline(dirs), SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "dirs.database"), "symlink escape must be rejected: " + f);
    }

    @Test
    void numericBoundsAreEnforced(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put("threads", 0);                    // below min
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("max_files", 0);                 // below min
        proc.put("batch", batch);
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("enabled", true);
        dup.put("retention_days", 0);              // data-loss footgun
        proc.put("duplicate_check", dup);
        raw.put("processing", proc);

        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "processing.threads"));
        assertTrue(hasError(f, "processing.batch.max_files"));
        assertTrue(hasError(f, "retention_days"));
    }

    @Test
    void threadsAboveCpuCapIsRejected(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> proc = new LinkedHashMap<>();
        proc.put("threads", 99999);
        raw.put("processing", proc);
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "processing.threads"), "absurd thread count must be rejected: " + f);
    }

    @Test
    void unknownOutputFormatAndCompressionRejected(@TempDir Path root) {
        Map<String, Object> raw = pipeline(safeDirs(root));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("format", "EXE");          // not in allow-list
        output.put("compression", "rar");     // not in allow-list
        raw.put("output", output);
        List<Finding> f = ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "output.format"));
        assertTrue(hasError(f, "output.compression"));
    }

    @Test
    void enrichmentOutsideRootIsRejected(@TempDir Path root) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "ENR");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("database", root.resolve("events").toString());
        raw.put("input", in);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("database", Path.of("/var/exfil").toAbsolutePath().toString()); // escapes
        out.put("format", "PARQUET");
        raw.put("output", out);
        List<Finding> f = ConfigSafetyValidator.check("enrichment", raw, SafetyPolicy.withRoots(root));
        assertTrue(hasError(f, "output.database"), "enrichment output escaping the root must be rejected: " + f);
    }

    @Test
    void nonPathTypesHaveNoSafetySurface(@TempDir Path root) {
        Map<String, Object> job = Map.of("job", Map.of("name", "j", "cron", "0 2 * * *"));
        assertTrue(ConfigSafetyValidator.check("job", job, SafetyPolicy.withRoots(root)).isEmpty());
        assertTrue(ConfigSafetyValidator.check("schema", Map.of(), SafetyPolicy.withRoots(root)).isEmpty());
    }

    @Test
    void nullRawAndDefaultPolicyAreSafe() {
        assertTrue(ConfigSafetyValidator.check("pipeline", null, null).isEmpty());
        assertNotNull(SafetyPolicy.defaultPolicy().allowedRoots());
        assertFalse(SafetyPolicy.defaultPolicy().allowedRoots().isEmpty(), "defaults to user.dir");
    }
}
