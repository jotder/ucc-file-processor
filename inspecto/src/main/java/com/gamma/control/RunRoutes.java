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
import com.gamma.service.CollectorService;
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
        // Optional ?limit=&offset= (ui-design-review R6a) — absent limit returns every pipeline, unchanged.
        api.get("/runs", (e, m) -> ApiContext.paged(api.service().pipelines(), e));
        // Register a new pipeline from a config on disk under the write root (control scope).
        // Registration is a workbench-authoring action (W6: canAuthorWorkbench); trigger/pause/resume/
        // reprocess below are operational (canOperateRuns) — both a no-op on Personal (rbac-groundwork.md §2).
        api.post("/runs", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createPipeline(api, e, api.body(e))));
        api.post("/runs/([^/]+)/trigger", ApiContext.withCapability("canOperateRuns", (e, m) ->
                triggerPipeline(api, e, ApiContext.name(m))));
        // W5b async: poll one manual run by id (the id returned by the 202 trigger above). Single-segment after
        // /runs/runs/, so it never collides with the /runs list or the /runs/{name}/<audit> routes.
        api.get("/runs/runs/([^/]+)", (e, m) -> pipelineRunById(api, ApiContext.name(m)));
        api.post("/runs/([^/]+)/pause", ApiContext.withCapability("canOperateRuns", (e, m) -> {
            if (!api.service().pause(ApiContext.name(m))) throw notFound(ApiContext.name(m));
            return Map.of("pipeline", ApiContext.name(m), "paused", true);
        }));
        api.post("/runs/([^/]+)/resume", ApiContext.withCapability("canOperateRuns", (e, m) -> {
            if (!api.service().resume(ApiContext.name(m))) throw notFound(ApiContext.name(m));
            return Map.of("pipeline", ApiContext.name(m), "paused", false);
        }));

        api.get("/runs/([^/]+)/commits",    (e, m) -> api.service().statusStore().committedBatches(cfg(api, m)));
        api.get("/runs/([^/]+)/batches",    (e, m) -> api.service().statusStore().batches(cfg(api, m)));
        api.get("/runs/([^/]+)/files",      (e, m) -> api.service().statusStore().files(cfg(api, m)));
        api.get("/runs/([^/]+)/lineage",    (e, m) -> api.service().statusStore().lineage(cfg(api, m), ApiContext.query(e, "batchId")));
        api.get("/runs/([^/]+)/quarantine", (e, m) -> api.service().statusStore().quarantine(cfg(api, m)));
        // Inbox/processing status: files still pending (matched, not yet processed) + whether the
        // pipeline is currently ingesting. Complements the audit-backed /files (processed history).
        api.get("/runs/([^/]+)/pending",    (e, m) ->
                api.service().inboxStatus(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m))));

        api.post("/runs/([^/]+)/reprocess", ApiContext.withCapability("canOperateRuns", (e, m) -> {
            var path = api.service().pathFor(ApiContext.name(m)).orElseThrow(() -> notFound(ApiContext.name(m)));
            String batchId = ApiContext.str(api.body(e), "batchId");
            if (batchId == null) throw new ApiException(400, "body must include 'batchId'");
            ReprocessCommand.run(path.toString(), batchId);
            return Map.of("pipeline", ApiContext.name(m), "batchId", batchId, "status", "reprocessed");
        }));

        api.post("/trigger", ApiContext.withCapability("canOperateRuns", (e, m) -> api.service().runAllOnce()));

        // ── v2.8.0: aggregated reports (status snapshot + batch-audit rollup) ──
        // v2.10.0: ?from=&to= scope the rollup to a date range (inclusive; date or datetime).
        api.get("/status", (e, m) -> api.service().reports().statusReport());
        api.get("/report", (e, m) -> api.service().reports().serviceReport(window(e)));
        api.get("/runs/([^/]+)/report", (e, m) -> {
            cfg(api, m);   // 404 if no such pipeline
            return api.service().reports().batchReport(ApiContext.name(m), window(e));
        });
    }

    /**
     * {@code POST /runs/{name}/trigger} — fire a pipeline. On the v1 surface returns {@code 202} + {@code {runId,…}}
     * + a {@code Location} to poll (async, off the ingest lock — mirrors the job trigger); the legacy surface keeps
     * its unchanged {@code 200} {@link com.gamma.inspector.MultiCollectorProcessor.RunResult} body. 404 if no such pipeline.
     */
    private Object triggerPipeline(ApiContext api, HttpExchange e, String name) throws IOException {
        if (ApiContext.v1(e)) {
            String runId = api.service().triggerRunAsync(name).orElseThrow(() -> notFound(name));
            e.getResponseHeaders().set("Location", "/api/v1/runs/runs/" + runId);
            return ApiContext.respondJson(e, 202, Map.of("runId", runId, "pipeline", name, "status", "running"));
        }
        return api.service().runPipeline(name).orElseThrow(() -> notFound(name));
    }

    /** {@code GET /runs/runs/{runId}} — poll one manual pipeline run's status (W5b); 404 once evicted or unknown. */
    private Object pipelineRunById(ApiContext api, String runId) {
        CollectorService.PipelineRun r = api.service().pipelineRunById(runId)
                .orElseThrow(() -> new ApiException(404, "no run '" + runId + "'"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId());
        m.put("pipeline", r.pipeline());
        m.put("trigger", r.trigger());
        m.put("status", r.status());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("total", r.total());
        m.put("failed", r.failed());
        m.put("message", r.message());
        return m;
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
     * {@link CollectorService.PipelineView} is returned.
     */
    private Object createPipeline(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline registration");
        String configPath = ApiContext.str(body, "configPath");
        if (configPath == null || configPath.isBlank())
            throw new ApiException(400, "body must include 'configPath'");

        Path candidate = Path.of(configPath.trim());
        Path resolved = WriteGates.jail(writeRoot,
                candidate.isAbsolute() ? candidate : writeRoot.resolve(candidate), "configPath");
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

        CollectorService.PipelineView view = api.service().pipelines().stream()
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
