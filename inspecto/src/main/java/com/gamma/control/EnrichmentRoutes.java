package com.gamma.control;

import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.report.ReportService;
import com.gamma.service.EnrichmentService;
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
 * Stage-2 enrichment routes ({@code /enrichment*}, v2.9.0): the per-job run audit, output lineage,
 * and the run-audit rollup report. Extracted verbatim from {@link ControlApi}: identical routes,
 * order, statuses and shapes. {@code POST /enrichment} (v5.1.0) hot-registers an authored config,
 * pairing with {@code POST /config/write type=enrichment} the way {@code POST /runs} pairs with a
 * pipeline write.
 */
final class EnrichmentRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/enrichment", (e, m) -> enrichment(api).views());
        api.post("/enrichment", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> registerEnrichment(api, e, api.body(e))));
        api.get("/enrichment/([^/]+)/runs", (e, m) -> enrichment(api).runs(enrichJob(api, m)));
        api.get("/enrichment/([^/]+)/lineage", (e, m) ->
                enrichment(api).lineage(enrichJob(api, m), ApiContext.query(e, "runId")));
        api.get("/enrichment/([^/]+)/report", (e, m) ->
                api.service().reports().enrichmentReport(enrichJob(api, m), window(e)));
    }

    /** The enrichment service, or a 404 when no enrichment jobs are registered. */
    private EnrichmentService enrichment(ApiContext api) {
        return api.service().enrichmentService()
                .orElseThrow(() -> new ApiException(404, "no enrichment jobs registered"));
    }

    /** Resolve a path-named enrichment job to its name, 404 when it is not registered. */
    private String enrichJob(ApiContext api, Matcher m) {
        String n = ApiContext.name(m);
        if (enrichment(api).config(n).isEmpty())
            throw new ApiException(404, "no enrichment job named '" + n + "'");
        return n;
    }

    /**
     * Hot-register (or replace, keyed by the in-file {@code name}) a Stage-2 enrichment job from a
     * config already on disk under the write root (v5.1.0). No restart: event triggers apply from
     * the next committed batch; a new job's completeness schedule is armed now (an interval
     * <em>change</em> on an existing name applies on restart — the scheduler has no cancel).
     *
     * <p>Body {@code {"configPath":"…"}} — absolute, or relative to {@code -Dassist.write.root}.
     * Gated fail-closed: write root unset → 503; missing {@code configPath} → 400; a path
     * resolving outside the root → 403; no file there → 404; spec / hard-fail safety ERRORs or a
     * config that does not load → 422 (findings returned). Replacement is the documented upsert
     * semantic (the guided editor re-registers on every save), so there is no 409.
     */
    private Object registerEnrichment(ApiContext api, HttpExchange ex, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "enrichment registration");
        String configPath = ApiContext.str(body, "configPath");
        if (configPath == null || configPath.isBlank())
            throw new ApiException(400, "body must include 'configPath'");

        Path candidate = Path.of(configPath.trim());
        Path resolved = WriteGates.jail(writeRoot,
                candidate.isAbsolute() ? candidate : writeRoot.resolve(candidate), "configPath");
        if (!Files.isRegularFile(resolved))
            throw new ApiException(404, "no config file at "
                    + writeRoot.relativize(resolved).toString().replace('\\', '/'));

        // Validate before registering: spec + the hard-fail safety gate. Block on ERRORs — the
        // file may have been placed here without going through POST /config/write.
        Map<String, Object> raw;
        try {
            raw = ConfigLoader.filesystem().decode(resolved.toString());
        } catch (RuntimeException parse) {
            throw new ApiException(422, "config does not parse: " + parse.getMessage());
        }
        List<Finding> findings = new ArrayList<>(
                ConfigLoader.filesystem().validate(ConfigSpecs.enrichment(), raw));
        findings.addAll(ConfigSafetyValidator.check("enrichment", raw, SafetyPolicy.defaultPolicy()));
        if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
            return ApiContext.respondJson(ex, 422, Map.of("registered", false,
                    "error", "config has ERROR-level findings; not registered", "findings", findings));
        }

        EnrichmentConfig cfg;
        try {
            cfg = EnrichmentConfig.load(resolved.toString());   // structural validation
        } catch (RuntimeException invalid) {
            throw new ApiException(422, "config is not a valid enrichment: " + invalid.getMessage());
        }
        api.service().registerEnrichment(cfg);

        EnrichmentService.JobView view = enrichment(api).views().stream()
                .filter(v -> v.name().equals(cfg.name())).findFirst().orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("registered", true);
        r.put("name", cfg.name());
        r.put("path", writeRoot.relativize(resolved).toString().replace('\\', '/'));
        r.put("job", view);
        r.put("findings", findings);   // warnings only at this point
        return r;
    }

    /** Build a report {@link ReportService.Window} from {@code ?from=&to=}. */
    private static ReportService.Window window(HttpExchange ex) {
        return ReportService.Window.of(ApiContext.query(ex, "from"), ApiContext.query(ex, "to"));
    }
}
