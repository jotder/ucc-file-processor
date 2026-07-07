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
 * </pre>
 *
 * <p>Space-scoped through the standard {@code /spaces/{id}/…} request seam: the UI calls the bare
 * {@code /settings/branding} for the active space, or {@code /spaces/{id}/settings/branding} to edit any
 * space. Stored as {@code branding.toon} in the bound space's config tree ({@link ApiContext#writeRoot()}),
 * so branding writes share the same read-only ({@code 503}) gate as config writes.
 */
final class SettingsRoutes implements RouteModule {

    private static final String BRANDING_FILE = "branding.toon";
    /** Reject an over-large inline logo (defence-in-depth; the UI already caps ~200 KB). */
    private static final int MAX_LOGO_CHARS = 512 * 1024;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/branding", (e, m) -> readBranding(api));
        api.put("/settings/branding", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeBranding(api, api.body(e))));
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
}
