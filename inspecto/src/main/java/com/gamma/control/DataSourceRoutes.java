package com.gamma.control;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.event.EventLog;
import com.gamma.service.BundleExporter;
import com.gamma.service.BundleImporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.DataSourceBundleResolver;
import com.gamma.service.SourceService;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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
                .map(SourceService.PipelineView::name).collect(Collectors.toSet());
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
