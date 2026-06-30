package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.ReprocessCommand;
import com.gamma.report.ReportService;
import com.gamma.service.SourceService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Core pipeline routes ({@code /runs*}, {@code /trigger}, {@code /status}, {@code /report}):
 * the pipeline registry + lifecycle (list / register / trigger / pause / resume), the audit reads
 * backed by the {@link com.gamma.service.StatusStore} (commits / batches / files / lineage /
 * quarantine / pending / reprocess), and the aggregated reports. Extracted verbatim from
 * {@link ControlApi}: identical routes, order, statuses and shapes.
 */
final class RunRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/runs", (e, m) -> api.service().pipelines());
        // Register a new pipeline from a config on disk under the write root (control scope).
        api.post("/runs", (e, m) -> createPipeline(api, e, api.body(e)));
        api.post("/runs/([^/]+)/trigger", (e, m) ->
                api.service().runPipeline(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m))));
        api.post("/runs/([^/]+)/pause", (e, m) -> {
            if (!api.service().pause(ApiContext.name(m))) throw notFound(ApiContext.name(m));
            return Map.of("pipeline", ApiContext.name(m), "paused", true);
        });
        api.post("/runs/([^/]+)/resume", (e, m) -> {
            if (!api.service().resume(ApiContext.name(m))) throw notFound(ApiContext.name(m));
            return Map.of("pipeline", ApiContext.name(m), "paused", false);
        });

        api.get("/runs/([^/]+)/commits",    (e, m) -> api.service().statusStore().committedBatches(cfg(api, m)));
        api.get("/runs/([^/]+)/batches",    (e, m) -> api.service().statusStore().batches(cfg(api, m)));
        api.get("/runs/([^/]+)/files",      (e, m) -> api.service().statusStore().files(cfg(api, m)));
        api.get("/runs/([^/]+)/lineage",    (e, m) -> api.service().statusStore().lineage(cfg(api, m), ApiContext.query(e, "batchId")));
        api.get("/runs/([^/]+)/quarantine", (e, m) -> api.service().statusStore().quarantine(cfg(api, m)));
        // Inbox/processing status: files still pending (matched, not yet processed) + whether the
        // pipeline is currently ingesting. Complements the audit-backed /files (processed history).
        api.get("/runs/([^/]+)/pending",    (e, m) ->
                api.service().inboxStatus(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m))));

        api.post("/runs/([^/]+)/reprocess", (e, m) -> {
            var path = api.service().pathFor(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m)));
            String batchId = ApiContext.str(api.body(e), "batchId");
            if (batchId == null) throw new ApiException(400, "body must include 'batchId'");
            ReprocessCommand.run(path.toString(), batchId);
            return Map.of("pipeline", ApiContext.name(m), "batchId", batchId, "status", "reprocessed");
        });

        api.post("/trigger", (e, m) -> api.service().runAllOnce());

        // ── v2.8.0: aggregated reports (status snapshot + batch-audit rollup) ──
        // v2.10.0: ?from=&to= scope the rollup to a date range (inclusive; date or datetime).
        api.get("/status", (e, m) -> api.service().reports().statusReport());
        api.get("/report", (e, m) -> api.service().reports().serviceReport(window(e)));
        api.get("/runs/([^/]+)/report", (e, m) -> {
            cfg(api, m);   // 404 if no such pipeline
            return api.service().reports().batchReport(ApiContext.name(m), window(e));
        });
    }

    private PipelineConfig cfg(ApiContext api, Matcher m) {
        return api.service().configFor(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m)));
    }

    private static ApiException notFound(String name) {
        return new ApiException(404, "no pipeline named '" + name + "'");
    }

    /** Build a report {@link ReportService.Window} from {@code ?from=&to=}. */
    private static ReportService.Window window(HttpExchange ex) {
        return ReportService.Window.of(ApiContext.query(ex, "from"), ApiContext.query(ex, "to"));
    }

    /**
     * Register a new pipeline from a config already on disk under the write root (v4.1.0, scope
     * {@code control}). Pairs with {@code POST /config/write}: author + persist a {@code .toon}
     * there, then register it so the running service processes it on the next poll cycle — no
     * restart. (Registration is in-memory; a registered pipeline survives a restart only if its
     * file also lies under a config dir the service is launched with — keep {@code assist.write.root}
     * inside the launched config tree to get both.)
     *
     * <p>Body {@code {"configPath":"…"}} — absolute, or relative to {@code -Dassist.write.root}.
     * Gated fail-closed: registration disabled unless the write root is set → 503; missing
     * {@code configPath} → 400; a path resolving outside the root → 403; no file there → 404; a
     * config that fails spec / hard-fail safety (R6) validation → 422 (findings returned); an id
     * colliding with a <em>different</em> registered pipeline → 409. On success the new pipeline's
     * {@link SourceService.PipelineView} is returned.
     */
    private Object createPipeline(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = api.writeRoot();
        if (writeRoot == null)
            throw new ApiException(503, "pipeline registration disabled: set -Dassist.write.root to enable");
        String configPath = ApiContext.str(body, "configPath");
        if (configPath == null || configPath.isBlank())
            throw new ApiException(400, "body must include 'configPath'");

        Path candidate = Path.of(configPath.trim());
        Path resolved = (candidate.isAbsolute() ? candidate : writeRoot.resolve(candidate)).normalize();
        if (!resolved.startsWith(writeRoot))
            throw new ApiException(403, "configPath escapes the write root");
        if (!Files.isRegularFile(resolved))
            throw new ApiException(404, "no config file at "
                    + writeRoot.relativize(resolved).toString().replace('\\', '/'));

        // Validate before registering: spec + the hard-fail safety gate (R6). Block on ERRORs —
        // the file may have been placed here without going through POST /config/write.
        Map<String, Object> raw;
        try {
            raw = ConfigLoader.filesystem().decode(resolved.toString());
        } catch (RuntimeException parse) {
            throw new ApiException(422, "config does not parse: " + parse.getMessage());
        }
        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), raw));
        findings.addAll(ConfigSafetyValidator.check("pipeline", raw, SafetyPolicy.defaultPolicy()));
        // ERROR here: registration loads the config for real, so an unresolvable schema_file is a
        // guaranteed failure — block with a structured, field-anchored finding instead of letting
        // PipelineConfig.load() surface it as an opaque "config is not a valid pipeline" 422.
        findings.addAll(ConfigRoutes.schemaFileFindings("pipeline", raw, Severity.ERROR));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("registered", false,
                    "error", "config has ERROR-level findings; not registered", "findings", findings));
        }

        String id;
        try {
            id = api.service().registerPipeline(resolved);
        } catch (IllegalStateException collision) {
            throw new ApiException(409, collision.getMessage());
        } catch (RuntimeException invalid) {
            throw new ApiException(422, "config is not a valid pipeline: " + invalid.getMessage());
        }

        SourceService.PipelineView view = api.service().pipelines().stream()
                .filter(p -> p.name().equals(id)).findFirst().orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("registered", true);
        r.put("id", id);
        r.put("path", writeRoot.relativize(resolved).toString().replace('\\', '/'));
        r.put("pipeline", view);
        r.put("findings", findings);   // warnings only at this point
        return r;
    }
}
