package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.service.BundleExporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.DataSourceBundleResolver;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.NoSuchElementException;

/**
 * Per-space data-source bundle endpoints — config/metadata export (Stage 6). Reached through the
 * {@code /spaces/{id}/} request seam, so the handlers act on the bound space ({@code api.service()} +
 * {@code api.writeRoot()} = that space's {@code config/} dir):
 * <pre>
 *   GET /spaces/{id}/datasources                  list the space's data-source ids (pipeline names)  [v4.8.0]
 *   GET /spaces/{id}/datasources/{ds}/export      download one data source's bundle as a zip          [v4.8.0]
 *   GET /spaces/{id}/export                        download the whole space (config tree + space.toon) [v4.8.0]
 * </pre>
 *
 * <p>A bundle zip is config + metadata only (no ingested data — that is roadmap): the relevant TOON files
 * under a config-relative path plus a {@code bundle.toon} manifest, built by {@link BundleExporter}. All
 * three require filesystem access (a write root); without one they {@code 503}.
 */
final class DataSourceRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/datasources", (e, m) -> resolver(api).dataSourceIds());
        api.get("/datasources/([^/]+)/export", (e, m) -> exportDataSource(api, e, ApiContext.name(m)));
        api.get("/export", (e, m) -> exportSpace(api, e));
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
