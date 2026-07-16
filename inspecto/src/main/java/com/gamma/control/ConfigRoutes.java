package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.ConfigValidator;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.exec.ComponentPreview;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative-config routes ({@code /config/spec}, {@code /validate}, {@code /config/write},
 * {@code DELETE /config/&#123;type&#125;/&#123;name&#125;}; v3.2.0/v4.1.0/v5.1.0): describe a config
 * type's spec, validate a saved file or an unsaved draft, persist a validated draft (write-root
 * jailed, atomic), and discard one (draft delete; never an active pipeline). Extracted verbatim from
 * {@link ControlApi}: identical routes, statuses, gating order and on-disk behaviour.
 *
 * <p>{@link #schemaFileFindings} is shared with the pipeline-registration route that stays on
 * {@link ControlApi}; it lives here with the rest of the config-validation logic.
 */
final class ConfigRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ConfigRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/config/spec/(.+)", (e, m) -> {
            ConfigSpec spec = ConfigSpecs.forType(ApiContext.name(m));
            if (spec == null) throw new ApiException(404, "unknown config type: " + ApiContext.name(m));
            return spec;
        });
        api.post("/validate", (e, m) -> validate(api.body(e)));
        // Parse a raw sample with a draft's parsing: settings — stateless, scratch-only (stream
        // onboarding's sample-as-thread; the raw→parsed hop).
        api.post("/config/preview/parsing", (e, m) -> previewParsing(api.body(e)));
        // Requires canAuthorWorkbench (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/config/write", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> writeConfig(api, e, api.body(e))));
        // Draft discard (stream onboarding): delete a config file under the write root — never an
        // active pipeline. Optional ?subdir= mirrors /config/write's subdir.
        api.delete("/config/([^/]+)/([^/]+)", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> deleteConfig(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        // Draft read-back (stream onboarding resume): return a config file's decoded content.
        // Registered after /config/spec/…, which therefore keeps serving type="spec" lookups.
        api.get("/config/([^/]+)/([^/]+)",
                (e, m) -> readConfig(api, e, ApiContext.name(m), ApiContext.param(m, 2)));
    }

    private Object validate(Map<String, Object> body) throws IOException {
        String configPath = ApiContext.str(body, "configPath");
        if (configPath != null) {
            PipelineConfig cfg = PipelineConfig.load(configPath);
            List<String> warnings = ConfigValidator.validate(cfg);
            List<Finding> findings = ConfigLoader.filesystem()
                    .validate(ConfigSpecs.pipeline(), ConfigLoader.filesystem().decode(configPath));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pipeline", cfg.identity().pipelineName());
            r.put("warnings", warnings);     // legacy string form (back-compat)
            r.put("findings", findings);     // structured form (v3.2.0)
            r.put("clean", warnings.isEmpty());
            return r;
        }
        String type = ApiContext.str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>)) {
            throw new ApiException(400,
                    "body must include 'configPath', or 'type' + 'config' (a draft config map)");
        }
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        // Pre-flight: warn when a pipeline draft's schema_file won't resolve on this server —
        // registration would otherwise fail later with an opaque error (v4.1.0).
        findings.addAll(schemaFileFindings(type, draft, Severity.WARNING));
        // Opt-in hard-fail safety gate (R6): merged in only when the caller asks, so the default
        // /validate response is byte-for-byte unchanged for existing callers.
        boolean safety = "true".equalsIgnoreCase(String.valueOf(body.get("safety")));
        if (safety) {
            findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("findings", findings);
        r.put("safetyChecked", safety);
        r.put("clean", findings.isEmpty());
        return r;
    }

    private Object writeConfig(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config write");

        String type = ApiContext.str(body, "type");
        Object cfgObj = body.get("config");
        if (type == null || !(cfgObj instanceof Map<?, ?>))
            throw new ApiException(400, "body must include 'type' and 'config' (a draft config map)");
        ConfigSpec spec = ConfigSpecs.forType(type);
        if (spec == null) throw new ApiException(404, "unknown config type: " + type);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;

        // Gate: spec validation + the hard-fail safety check (R6). Block on ERRORs; warnings pass.
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(spec, draft));
        findings.addAll(ConfigSafetyValidator.check(type, draft, SafetyPolicy.defaultPolicy()));
        // Warning only: the save still succeeds (the schema file may be created afterwards), but
        // the operator learns now that Register would fail on this host.
        findings.addAll(schemaFileFindings(type, draft, Severity.WARNING));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("type", type, "written", false,
                    "error", "config has ERROR-level findings; not written", "findings", findings));
        }

        // Filename from the config's own identity field — no caller-controlled path component.
        String idField = identityField(type);
        String rawName = dottedString(draft, idField);
        if (rawName == null || rawName.isBlank())
            throw new ApiException(422, "config is missing its identity field '" + idField + "'");
        String fileName = WriteGates.safeName(rawName, "config name");

        // Resolve under the write root; an optional subdir must stay inside it (path jail).
        Path dir = writeRoot;
        String subdir = ApiContext.str(body, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = WriteGates.jail(writeRoot, dir.resolve(fileName + ".toon"), "resolved path");

        boolean exists = Files.exists(target);
        boolean overwrite = "true".equalsIgnoreCase(String.valueOf(body.get("overwrite")));
        WriteGates.conflictIf(exists && !overwrite,
                "file exists: " + writeRoot.relativize(target).toString().replace('\\', '/')
                        + " (pass overwrite:true to replace)");

        // Encode and write atomically: a partial/concurrent reader never sees a half-written file.
        byte[] bytes = ConfigCodec.toToon(draft).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(target, bytes, ".cfg-");
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        log.info("[CONFIG-WRITE] type={} wrote {} ({} bytes, overwrote={})", type, rel, bytes.length, exists);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("written", true);
        r.put("path", rel);
        r.put("name", fileName);
        r.put("bytes", bytes.length);
        r.put("overwritten", exists);
        r.put("findings", findings);   // warnings only at this point (errors would have 422'd)
        return r;
    }

    /**
     * {@code DELETE /config/{type}/{name}} — discard a config file under the write root (the
     * onboarding draft-discard path, v5.1.0). Fail-closed gate order: write-root 503 → unknown type
     * 404 → unsafe name 422 → path jail 403 → missing file 404 → active pipeline 409 → single
     * atomic delete. An {@code active: true} pipeline is never deleted — deactivate it first.
     */
    private Object deleteConfig(ApiContext api, HttpExchange ex, String type, String name) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config delete");
        if (ConfigSpecs.forType(type) == null) throw new ApiException(404, "unknown config type: " + type);
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.query(ex, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = WriteGates.jail(writeRoot, dir.resolve(fileName + ".toon"), "resolved path");
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target)) throw new ApiException(404, "no such config: " + rel);

        if ("pipeline".equals(type)) {
            Map<String, Object> raw = ConfigLoader.filesystem().decode(target.toString());
            WriteGates.conflictIf(
                    Boolean.parseBoolean(String.valueOf(raw.getOrDefault("active", "false"))),
                    "pipeline '" + fileName + "' is active; deactivate (active: false) before deleting");
        }

        Files.delete(target);
        log.info("[CONFIG-DELETE] type={} deleted {}", type, rel);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("name", fileName);
        r.put("deleted", true);
        r.put("path", rel);
        return r;
    }

    /**
     * {@code GET /config/{type}/{name}} — read a config file back as its decoded map (the
     * onboarding resume path, v5.1.0). Same resolution and gate order as {@code DELETE}:
     * write-root 503 → unknown type 404 → unsafe name 422 → path jail 403 → missing file 404.
     * Ungated like the other reads; the write/delete mutations stay capability-gated.
     */
    private Object readConfig(ApiContext api, HttpExchange ex, String type, String name) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "config read");
        if (ConfigSpecs.forType(type) == null) throw new ApiException(404, "unknown config type: " + type);
        String fileName = WriteGates.safeName(name, "config name");

        Path dir = writeRoot;
        String subdir = ApiContext.query(ex, "subdir");
        if (subdir != null && !subdir.isBlank()) {
            Path sub = Path.of(subdir.trim());
            if (sub.isAbsolute()) throw new ApiException(400, "subdir must be relative");
            dir = WriteGates.jail(writeRoot, writeRoot.resolve(sub), "subdir");
        }
        Path target = WriteGates.jail(writeRoot, dir.resolve(fileName + ".toon"), "resolved path");
        String rel = writeRoot.relativize(target).toString().replace('\\', '/');
        if (!Files.isRegularFile(target)) throw new ApiException(404, "no such config: " + rel);

        Map<String, Object> config = ConfigLoader.filesystem().decode(target.toString());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("name", fileName);
        r.put("path", rel);
        r.put("config", config);
        return r;
    }

    /** Character cap on {@code sample_text} — a preview sample, not a data upload. */
    private static final int MAX_SAMPLE_CHARS = 1_000_000;

    /**
     * {@code POST /config/preview/parsing} — parse a raw sample with a pipeline draft's
     * {@code parsing:} settings and return the produced columns/rows (stream onboarding,
     * v5.1.0). Stateless and scratch-only: body {@code {config:{…}, sample_text}} where
     * {@code config} is a full pipeline draft map (the same shape {@code /validate} takes).
     * The draft is interpreted by the real config parser and the sample is read with the same
     * DuckDB idioms the ingest engine uses ({@link ComponentPreview#parsing}), so what the
     * builder sees is what the engine would parse. Config/parse problems are the caller's
     * (422 with the reason), never a server error.
     */
    private Object previewParsing(Map<String, Object> body) {
        Object cfgObj = body.get("config");
        String sample = ApiContext.str(body, "sample_text");
        if (!(cfgObj instanceof Map<?, ?>) || sample == null || sample.isBlank())
            throw new ApiException(400, "body must include 'config' (a pipeline draft map) and 'sample_text'");
        if (sample.length() > MAX_SAMPLE_CHARS)
            throw new ApiException(400, "sample_text too large (max " + MAX_SAMPLE_CHARS + " chars)");
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) cfgObj;
        PipelineConfig cfg;
        try {
            cfg = PipelineConfig.fromMap(draft);
        } catch (Exception invalid) {
            throw new ApiException(422, "config is not a valid pipeline draft: " + invalid.getMessage());
        }
        try {
            ComponentPreview.GrammarResult r = ComponentPreview.parsing(cfg, sample);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("frontend", frontendOf(cfg));
            out.put("columns", r.columns());
            out.put("rowCount", r.rowCount());
            out.put("rows", r.rows());
            out.put("rejectedRows", r.rejectedRows());
            return out;
        } catch (IllegalArgumentException unsupported) {
            throw new ApiException(422, unsupported.getMessage());
        } catch (Exception parseFail) {
            throw new ApiException(422, "sample does not parse with these settings: " + parseFail.getMessage());
        }
    }

    /** The parsing frontend a config resolves to (the same precedence the ingester applies). */
    private static String frontendOf(PipelineConfig cfg) {
        if (cfg.fixedWidth() != null) return "fixedwidth";
        if (cfg.json() != null) return "json";
        if (cfg.textRegex() != null) return "text_regex";
        if (cfg.schemas().ingesterClass() != null) return "plugin";
        return "delimited";
    }

    /**
     * Pre-flight check that a pipeline draft's schema reference(s) resolve on <em>this server's</em>
     * filesystem (v4.1.0). {@link PipelineConfig} resolves {@code schema_file} relative to the
     * process working directory, so a draft that validates clean can still fail at registration
     * with an opaque 422 — this surfaces it early, as a structured finding anchored to the field.
     * Checks both the legacy {@code processing.schema_file} and the multi-schema
     * {@code processing.schemas[].schema_file}. No-op for non-pipeline types.
     *
     * @param severity WARNING at validate/save time (the file may be created later, or the config
     *                 may be destined for another host); ERROR at register time (it will fail)
     */
    static List<Finding> schemaFileFindings(String type, Map<String, Object> draft, Severity severity) {
        if (!"pipeline".equals(type)) return List.of();
        Object procObj = draft.get("processing");
        if (!(procObj instanceof Map<?, ?> proc)) return List.of();
        List<Finding> out = new ArrayList<>();
        if (proc.get("schema_file") instanceof String s && !s.isBlank() && !Files.isRegularFile(Path.of(s)))
            out.add(new Finding(severity, "processing.schema_file", unresolvable(s)));
        if (proc.get("schemas") instanceof List<?> defs) {
            for (int i = 0; i < defs.size(); i++) {
                if (defs.get(i) instanceof Map<?, ?> def
                        && def.get("schema_file") instanceof String s && !s.isBlank()
                        && !Files.isRegularFile(Path.of(s)))
                    out.add(new Finding(severity, "processing.schemas[" + i + "].schema_file",
                            unresolvable(s)));
            }
        }
        return out;
    }

    private static String unresolvable(String schemaPath) {
        return "schema file does not resolve on the server: '" + schemaPath
                + "' (relative paths resolve against the server's working directory: "
                + Path.of("").toAbsolutePath() + ")";
    }

    /** Dotted path into the config map that holds a config's stable identity (its filename source). */
    private static String identityField(String type) {
        return switch (type) {
            case "job"    -> "job.name";
            case "schema" -> "raw.name";
            default       -> "name";   // pipeline, enrichment, meta
        };
    }

    /** Read a dotted key (e.g. {@code job.name}) from a nested config map, or {@code null} if absent. */
    private static String dottedString(Map<String, Object> map, String dotted) {
        Object cur = map;
        for (String seg : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(seg);
        }
        return cur == null ? null : String.valueOf(cur);
    }
}
