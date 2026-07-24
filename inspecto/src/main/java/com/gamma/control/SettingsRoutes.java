package com.gamma.control;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-space UI settings — small preference documents the UI fetches and saves per space:
 * <pre>
 *   GET /settings/branding   the space's {logoDataUrl, caption, footerText} (nulls = shipped defaults) [v4.10.0]
 *   PUT /settings/branding   replace the space's branding (write-root gated, capability-gated)         [v4.10.0]
 *   GET /settings/geo        the space's {tileServerUrl} (null = no self-hosted tile server)
 *   PUT /settings/geo        replace the space's geo/tile-server config (same gates as branding)
 *   GET /config/icon-map     the space's processor-icon map { "&lt;type&gt;": {glyph,color}, … } ({} = none) [v5.0.0]
 *   PUT /config/icon-map     replace the space's icon map (same gates as branding)                          [v5.0.0]
 * </pre>
 *
 * <p>Space-scoped through the standard {@code /spaces/{id}/…} request seam: the UI calls the bare
 * {@code /settings/branding} for the active space, or {@code /spaces/{id}/settings/branding} to edit any
 * space. Stored as {@code branding.toon} / {@code geo.toon} / {@code icon-map.toon} in the bound space's
 * config tree ({@link ApiContext#writeRoot()}), so settings writes share the same read-only ({@code 503}) gate
 * as config writes. ({@code /config/icon-map} keeps the UI's existing {@code IconMapService} path; it is a
 * per-space preference document like branding/geo, not a runnable-config route — hence its home here.)
 */
final class SettingsRoutes implements RouteModule {

    private static final String BRANDING_FILE = "branding.toon";
    private static final String GEO_FILE = "geo.toon";
    private static final String ICON_MAP_FILE = "icon-map.toon";
    /** Reject an over-large inline logo (defence-in-depth; the UI already caps ~200 KB). */
    private static final int MAX_LOGO_CHARS = 512 * 1024;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/branding", (e, m) -> ETags.respond(e, readBranding(api)));
        api.put("/settings/branding", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeBranding(api, api.body(e))));
        api.get("/settings/geo", (e, m) -> ETags.respond(e, readGeo(api)));
        api.put("/settings/geo", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeGeo(api, api.body(e))));
        api.get("/config/icon-map", (e, m) -> ETags.respond(e, readIconMap(api)));
        api.put("/config/icon-map", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeIconMap(api, api.body(e))));
    }

    private Object readBranding(ApiContext api) {
        Path root = api.writeRoot();
        BrandingSettings b = root == null ? BrandingSettings.EMPTY : BrandingSettings.read(root.resolve(BRANDING_FILE));
        return shape(b);
    }

    private Object writeBranding(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "branding write");
        String logo = ApiContext.str(body, "logoDataUrl");
        if (logo != null && logo.length() > MAX_LOGO_CHARS)
            throw new ApiException(422, "logoDataUrl too large (max " + MAX_LOGO_CHARS + " characters)");
        BrandingSettings b = new BrandingSettings(logo, ApiContext.str(body, "caption"), ApiContext.str(body, "footerText"));
        b.write(root.resolve(BRANDING_FILE));
        return shape(b);
    }

    /** The wire shape the UI's {@code BrandingService} expects — nulls kept so the client falls back to defaults. */
    private static Map<String, Object> shape(BrandingSettings b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logoDataUrl", b.logoDataUrl());
        m.put("caption", b.caption());
        m.put("footerText", b.footerText());
        return m;
    }

    private Object readGeo(ApiContext api) {
        Path root = api.writeRoot();
        GeoSettings g = root == null ? GeoSettings.EMPTY : GeoSettings.read(root.resolve(GEO_FILE));
        return geoShape(g);
    }

    private Object writeGeo(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "geo settings write");
        GeoSettings g = new GeoSettings(ApiContext.str(body, "tileServerUrl"));
        g.write(root.resolve(GEO_FILE));
        return geoShape(g);
    }

    /** The wire shape the UI's {@code GeoSettingsService} expects — null means "no self-hosted tile server". */
    private static Map<String, Object> geoShape(GeoSettings g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tileServerUrl", g.tileServerUrl());
        return m;
    }

    private Object readIconMap(ApiContext api) {
        Path root = api.writeRoot();
        IconMapSettings s = root == null ? IconMapSettings.EMPTY : IconMapSettings.read(root.resolve(ICON_MAP_FILE));
        return s.toWire();
    }

    @SuppressWarnings("unchecked")
    private Object writeIconMap(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "icon-map write");
        Map<String, IconMapSettings.Rule> rules = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> sub))
                throw new ApiException(422, "icon-map entry '" + entry.getKey() + "' must be an object {glyph, color}");
            String glyph = ApiContext.str((Map<String, Object>) sub, "glyph");
            String color = ApiContext.str((Map<String, Object>) sub, "color");
            if (glyph == null || glyph.isBlank() || color == null || color.isBlank())
                throw new ApiException(422, "icon-map entry '" + entry.getKey() + "' needs a glyph and a color");
            rules.put(entry.getKey(), new IconMapSettings.Rule(glyph.trim(), color.trim()));
        }
        IconMapSettings s = new IconMapSettings(rules);
        s.write(root.resolve(ICON_MAP_FILE));
        return s.toWire();
    }
}
