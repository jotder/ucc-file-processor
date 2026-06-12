package com.gamma.config.safety;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.RawConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The hard-fail config safety gate (M5 / v3.5.0; security guardrail R6). A config can pass
 * {@code ConfigSpecs} validation — be structurally <em>valid</em> — and still be <em>unsafe</em>:
 * write outside the workspace, oversubscribe the box, delete its own dedup markers, or target an
 * unknown output sink. This validator catches exactly that class, returning {@code ERROR}-severity
 * {@link Finding}s for any violation so an agent draft is rejected (and repaired) before it is ever
 * surfaced to a human.
 *
 * <p>It is intentionally <b>core</b> (not in the optional agent): the security boundary must hold
 * regardless of who produced the draft. It is pure JDK ({@code nio}), zero-dependency, and stateless.
 *
 * <h3>What it enforces</h3>
 * <ul>
 *   <li><b>Path jail</b> — every path-bearing field must resolve under an {@link SafetyPolicy#allowedRoots}
 *       root: reject UNC/network paths, {@code ..} escapes, anything outside the roots, and symlink
 *       escapes (the nearest existing ancestor's real path is re-checked).</li>
 *   <li><b>Numeric bounds</b> — threads, duckdb-threads, batch caps within policy; {@code skip_*} ≥ 0;
 *       and {@code retention_days} ≥ 1 when duplicate-check is on (else the first cleanup wipes every
 *       marker — a data-loss footgun the advisory validator only warns about).</li>
 *   <li><b>Output allow-list</b> — {@code output.format} and {@code output.compression} restricted to
 *       known-safe values; DuckLake targets require their connection fields when enabled.</li>
 * </ul>
 *
 * <p>Only the path-bearing config types ({@code pipeline}, {@code enrichment}) have a surface to gate;
 * {@code job}/{@code schema}/{@code meta} return no safety findings.
 *
 * @since 3.5.0
 */
public final class ConfigSafetyValidator {

    private ConfigSafetyValidator() {}

    private static final String[] PIPELINE_DIRS = {
            "dirs.poll", "dirs.database", "dirs.backup", "dirs.temp", "dirs.errors",
            "dirs.quarantine", "dirs.markers", "dirs.status_dir", "dirs.log_dir"
    };
    private static final String[] PIPELINE_SKIPS = {
            "processing.csv_settings.skip_header_lines", "processing.csv_settings.skip_junk_lines",
            "processing.csv_settings.skip_tail_lines", "processing.csv_settings.skip_tail_columns"
    };

    /**
     * Check {@code raw} (a decoded config map) against {@code policy}. Returns every safety violation
     * as an {@code ERROR} {@link Finding}; an empty list means the draft is safe. Never throws.
     */
    public static List<Finding> check(String configType, Map<String, Object> raw, SafetyPolicy policy) {
        List<Finding> out = new ArrayList<>();
        if (raw == null) return out;
        SafetyPolicy p = (policy == null) ? SafetyPolicy.defaultPolicy() : policy;
        String type = (configType == null) ? "" : configType.toLowerCase();
        switch (type) {
            case "pipeline" -> checkPipeline(raw, p, out);
            case "enrichment" -> checkEnrichment(raw, p, out);
            default -> { /* job / schema / meta: no path/numeric/output surface to gate */ }
        }
        return out;
    }

    // ── pipeline ─────────────────────────────────────────────────────────────────────

    private static void checkPipeline(Map<String, Object> raw, SafetyPolicy p, List<Finding> out) {
        for (String f : PIPELINE_DIRS) checkPath(raw, f, p, out);
        checkPath(raw, "output.ducklake.data_path", p, out);

        checkIntBound(raw, "processing.threads", 1, p.maxThreads(), out);
        checkIntBound(raw, "processing.duckdb_threads", -1, p.maxThreads(), out);
        checkIntBound(raw, "processing.batch.max_files", 1, p.maxBatchFiles(), out);

        Object maxBytes = RawConfig.at(raw, "processing.batch.max_bytes");
        if (maxBytes != null) {
            long v = longOr(raw, "processing.batch.max_bytes", 1);
            if (v <= 0) {
                out.add(Finding.error("processing.batch.max_bytes",
                        "batch.max_bytes must be > 0 (got " + v + ")"));
            } else if (v > p.maxBatchBytes()) {
                out.add(Finding.error("processing.batch.max_bytes",
                        "batch.max_bytes " + v + " exceeds the safety cap " + p.maxBatchBytes()));
            }
        }

        for (String f : PIPELINE_SKIPS) {
            if (RawConfig.at(raw, f) != null && RawConfig.intOr(raw, f, 0) < 0) {
                out.add(Finding.error(f, f + " must be >= 0"));
            }
        }

        if (RawConfig.boolOr(raw, "processing.duplicate_check.enabled", false)
                && RawConfig.at(raw, "processing.duplicate_check.retention_days") != null
                && RawConfig.intOr(raw, "processing.duplicate_check.retention_days", 90) <= 0) {
            out.add(Finding.error("processing.duplicate_check.retention_days",
                    "retention_days must be >= 1 when duplicate_check is enabled "
                            + "(else every dedup marker is wiped on the first cleanup)"));
        }

        checkOutput(raw, "output.format", "output.compression", p, out);
        checkDuckLake(raw, out);
    }

    // ── enrichment ───────────────────────────────────────────────────────────────────

    private static void checkEnrichment(Map<String, Object> raw, SafetyPolicy p, List<Finding> out) {
        checkPath(raw, "input.database", p, out);
        checkPath(raw, "output.database", p, out);
        checkPath(raw, "transform_file", p, out);

        Object refs = RawConfig.at(raw, "references");
        if (refs instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> ref && ref.get("path") != null) {
                    checkPathValue("references." + e.getKey() + ".path", ref.get("path").toString(), p, out);
                }
            }
        }
        checkOutput(raw, "output.format", "output.compression", p, out);
    }

    // ── path jail ────────────────────────────────────────────────────────────────────

    private static void checkPath(Map<String, Object> raw, String field, SafetyPolicy p, List<Finding> out) {
        String v = RawConfig.str(raw, field);
        if (v != null && !v.isBlank()) checkPathValue(field, v, p, out);
    }

    private static void checkPathValue(String field, String value, SafetyPolicy p, List<Finding> out) {
        String s = value.trim();
        if (s.startsWith("\\\\") || s.startsWith("//")) {
            out.add(Finding.error(field, "path '" + s + "' is a UNC/network path, which is not allowed"));
            return;
        }
        Path norm;
        try {
            norm = Paths.get(s).toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            out.add(Finding.error(field, "path '" + s + "' is not a valid path: " + ex.getMessage()));
            return;
        }
        if (!underAnyRoot(norm, p.allowedRoots())) {
            out.add(Finding.error(field, "path '" + s + "' resolves to " + norm
                    + ", outside the allowed roots " + p.allowedRoots()));
            return;
        }
        // Symlink escape: re-check the nearest existing ancestor's real path.
        try {
            Path existing = norm;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null) {
                Path real = existing.toRealPath();
                if (!underAnyRoot(real, p.allowedRoots())) {
                    out.add(Finding.error(field, "path '" + s
                            + "' escapes the allowed roots via a symlink (real path " + real + ")"));
                }
            }
        } catch (IOException ignored) {
            // Couldn't resolve a real path (perms, race); the normalised containment check already passed.
        }
    }

    private static boolean underAnyRoot(Path candidate, List<Path> roots) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) return true;
        }
        return false;
    }

    // ── numeric + output ───────────────────────────────────────────────────────────────

    private static void checkIntBound(Map<String, Object> raw, String field, int min, int max, List<Finding> out) {
        if (RawConfig.at(raw, field) == null) return;
        int n = RawConfig.intOr(raw, field, min); // unparseable -> in-bounds (a spec concern, not safety)
        if (n < min || n > max) {
            out.add(Finding.error(field, field + " must be in [" + min + ", " + max + "] (got " + n + ")"));
        }
    }

    private static void checkOutput(Map<String, Object> raw, String fmtField, String compField,
                                    SafetyPolicy p, List<Finding> out) {
        String fmt = RawConfig.str(raw, fmtField);
        if (fmt != null && !fmt.isBlank() && !p.allowedFormats().contains(fmt.trim().toUpperCase())) {
            out.add(Finding.error(fmtField, "output format '" + fmt + "' is not in the allow-list "
                    + p.allowedFormats()));
        }
        String comp = RawConfig.str(raw, compField);
        if (comp != null && !comp.isBlank() && !p.allowedCompression().contains(comp.trim().toLowerCase())) {
            out.add(Finding.error(compField, "compression '" + comp + "' is not in the allow-list "
                    + p.allowedCompression()));
        }
    }

    private static void checkDuckLake(Map<String, Object> raw, List<Finding> out) {
        if (!RawConfig.boolOr(raw, "output.ducklake.enabled", false)) return;
        for (String k : new String[]{"output.ducklake.catalog_url", "output.ducklake.data_path",
                "output.ducklake.table"}) {
            if (!RawConfig.present(raw, k)) {
                out.add(Finding.error(k, k + " is required when output.ducklake.enabled is true"));
            }
        }
    }

    private static long longOr(Map<String, Object> raw, String field, long def) {
        Object v = RawConfig.at(raw, field);
        if (v == null) return def;
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
