package com.gamma.control;

import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-space UI branding — the sidebar logo (an inline {@code data:} URL), the caption beneath it, and the
 * footer text. Persisted as {@code branding.toon} in the space's config tree (crash-safe TOON, mirroring
 * {@code SpaceContext.SpaceManifest}). Each field is {@code null} when unset, meaning "use the shipped
 * default"; on disk an unset field is stored as an empty string and read back as {@code null}.
 *
 * <p>The filename is deliberately not a {@code *_pipeline.toon}-style suffix, so the recursive config
 * discovery (which matches by suffix) never mistakes it for a runnable config.
 */
record BrandingSettings(String logoDataUrl, String caption, String footerText) {

    static final BrandingSettings EMPTY = new BrandingSettings(null, null, null);

    /** Write to {@code branding.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logo_data_url", logoDataUrl == null ? "" : logoDataUrl);
        m.put("caption", caption == null ? "" : caption);
        m.put("footer_text", footerText == null ? "" : footerText);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".branding-");
    }

    /** Read {@code branding.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (all defaults). */
    static BrandingSettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            return new BrandingSettings(
                    blankToNull(ToonHelper.opt(m, "logo_data_url", "")),
                    blankToNull(ToonHelper.opt(m, "caption", "")),
                    blankToNull(ToonHelper.opt(m, "footer_text", "")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
