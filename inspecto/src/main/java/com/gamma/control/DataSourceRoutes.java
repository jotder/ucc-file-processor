package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.event.EventLog;
import com.gamma.service.BundleExporter;
import com.gamma.service.BundleImporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.DataSourceBundleResolver;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Per-space data-source bundle endpoints — config/metadata export (Stage 6). Reached through the
 * {@code /spaces/{id}/} request seam, so the handlers act on the bound space ({@code api.service()} +
 * {@code api.writeRoot()} = that space's {@code config/} dir):
 * <pre>
 *   GET  /spaces/{id}/datasources                 list the space's data-source ids (pipeline names)  [v4.8.0]
 *   GET  /spaces/{id}/datasources/{ds}/export     download one data source's bundle as a zip          [v4.8.0]
 *   GET  /spaces/{id}/export                       download the whole space (config tree + space.toon) [v4.8.0]
 *   POST /spaces/{id}/import[?on_conflict=overwrite]  unpack a bundle zip into this space's config/    [v4.8.0]
 *   POST /spaces/{id}/import/preview               dry-run: what a bundle contains + conflicts + findings [v4.8.0]
 * </pre>
 *
 * <p>A bundle zip is config + metadata only (no ingested data — that is roadmap): the relevant TOON files
 * under a config-relative path plus a {@code bundle.toon} manifest, built by {@link BundleExporter}. All
 * require filesystem access (a write root); without one they {@code 503}.
 *
 * <p><b>Import</b> unpacks into {@code config/} (jailed against zip-slip), then makes the new configs live
 * immediately by re-registering pipelines + connections; it {@code 409}s on data-source id clashes unless
 * {@code ?on_conflict=overwrite}. Edited (overwritten) configs reload on the next poll cycle, as with
 * {@code /config/write}; imported jobs/metadata take effect on the next restart.
 */
final class DataSourceRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/datasources", (e, m) -> resolver(api).dataSourceIds());
        api.get("/datasources/([^/]+)/export", (e, m) -> exportDataSource(api, e, ApiContext.name(m)));
        api.get("/export", (e, m) -> exportSpace(api, e));
        api.post("/import", (e, m) -> importBundle(api, e));
        api.post("/import/preview", (e, m) -> previewImport(api, e));
    }

    /**
     * Dry-run an import: report what the bundle contains (kind, data sources, files), which data-source ids
     * would clash with this space, and the validation findings for each pipeline — writing nothing. Backs the
     * bulk-onboarding "preview before commit" step; pipelines are validated with the same spec + safety checks
     * as {@code /validate}.
     */
    private Object previewImport(ApiContext api, HttpExchange e) throws IOException {
        requireConfig(api);
        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(e.getRequestBody().readAllBytes());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }

        List<String> dataSources = BundleImporter.pipelineIds(bundle);
        Set<String> existing = api.service().pipelines().stream()
                .map(CollectorService.PipelineView::name).collect(Collectors.toSet());
        List<String> conflicts = dataSources.stream().filter(existing::contains).sorted().toList();

        Map<String, List<Finding>> findings = new LinkedHashMap<>();
        boolean valid = true;
        for (Map.Entry<String, byte[]> entry : bundle.configEntries().entrySet()) {
            if (!entry.getKey().endsWith("_pipeline.toon")) continue;
            List<Finding> fs = validatePipeline(entry.getValue());
            if (!fs.isEmpty()) findings.put(entry.getKey(), fs);
            if (fs.stream().anyMatch(f -> f.severity() == Severity.ERROR)) valid = false;
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", bundle.kind());
        r.put("sourceSpace", bundle.manifest().get("source_space"));
        r.put("dataSources", dataSources);
        r.put("files", new TreeSet<>(bundle.configEntries().keySet()));
        r.put("hasSpaceToon", bundle.spaceToon() != null);
        r.put("conflicts", conflicts);
        r.put("findings", findings);
        r.put("valid", valid);
        return r;
    }

    /**
     * Structural-spec findings for one pipeline TOON (a parse failure is itself an ERROR finding). Mirrors the
     * default {@code /validate}: spec validation only — the path-jail safety gate is a deploy-environment
     * concern (paths in a bundle belong to the source space) and is opt-in there, so it is not applied here.
     */
    private static List<Finding> validatePipeline(byte[] toon) {
        Map<String, Object> map;
        try {
            map = ConfigCodec.toMap(new String(toon, StandardCharsets.UTF_8));
        } catch (RuntimeException parseErr) {
            return List.of(new Finding(Severity.ERROR, "(parse)", "cannot parse pipeline: " + parseErr.getMessage()));
        }
        return ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), map);
    }

    /** Unpack a bundle zip into the bound space's {@code config/} and make the new configs live. */
    private Object importBundle(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        boolean overwrite = "overwrite".equalsIgnoreCase(ApiContext.query(e, "on_conflict"));

        BundleImporter.Bundle bundle;
        try {
            bundle = BundleImporter.parse(e.getRequestBody().readAllBytes());
        } catch (IllegalArgumentException bad) {
            throw new ApiException(400, bad.getMessage());
        }

        // Conflict = a bundle pipeline id that already exists in this space's registry.
        Set<String> existing = api.service().pipelines().stream()
                .map(CollectorService.PipelineView::name).collect(Collectors.toSet());
        List<String> conflicts = BundleImporter.pipelineIds(bundle).stream()
                .filter(existing::contains).sorted().toList();
        if (!conflicts.isEmpty() && !overwrite)
            return ApiContext.respondJson(e, 409, Map.of(
                    "error", "data-source id(s) already exist; re-send with ?on_conflict=overwrite to replace",
                    "conflicts", conflicts));

        List<String> written;
        try {
            written = BundleImporter.writeConfig(bundle, config);
        } catch (IllegalArgumentException jail) {
            throw new ApiException(400, jail.getMessage());
        }

        List<String> pipelines = new ArrayList<>();
        for (String rel : written) {
            Path p = config.resolve(rel);
            if (rel.endsWith("_pipeline.toon")) {
                try {
                    pipelines.add(api.service().registerPipeline(p));
                } catch (IllegalArgumentException invalid) {
                    throw new ApiException(422, "invalid pipeline " + rel + ": " + invalid.getMessage());
                } catch (IllegalStateException clash) {
                    throw new ApiException(409, clash.getMessage());
                }
            } else if (rel.endsWith("_connection.toon")) {
                api.service().registerConnection(ConnectionProfile.load(p));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", bundle.kind());
        body.put("imported", written);
        body.put("pipelines", pipelines);
        body.put("overwritten", overwrite && !conflicts.isEmpty());
        return body;
    }

    private Object exportDataSource(ApiContext api, HttpExchange e, String ds) throws IOException {
        Path config = requireConfig(api);
        DataSourceBundle bundle;
        try {
            bundle = new DataSourceBundleResolver(api.service(), config).resolve(ds);
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, notFound.getMessage());
        }
        return download(e, BundleExporter.exportDataSource(bundle, config, EventLog.currentSpaceId()),
                ds + ".bundle.zip");
    }

    private Object exportSpace(ApiContext api, HttpExchange e) throws IOException {
        Path config = requireConfig(api);
        Path spaceToon = config.getParent() == null ? null : config.getParent().resolve("space.toon");
        String space = EventLog.currentSpaceId();
        return download(e, BundleExporter.exportSpace(config, spaceToon, space), space + ".space.zip");
    }

    private static DataSourceBundleResolver resolver(ApiContext api) {
        return new DataSourceBundleResolver(api.service(), requireConfig(api));
    }

    /** The bound space's config dir, or {@code 503} when filesystem writes are disabled (no write root). */
    private static Path requireConfig(ApiContext api) {
        Path config = api.writeRoot();
        if (config == null) throw new ApiException(503, "filesystem access is disabled (no write root configured)");
        return config;
    }

    /** Write {@code zip} as an {@code application/zip} attachment download; returns {@link ApiContext#HANDLED}. */
    private static Object download(HttpExchange e, byte[] zip, String filename) throws IOException {
        e.getResponseHeaders().set("Content-Type", "application/zip");
        e.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        e.sendResponseHeaders(200, zip.length);
        e.getResponseBody().write(zip);
        return ApiContext.HANDLED;
    }
}
