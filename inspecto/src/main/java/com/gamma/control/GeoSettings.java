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
 * Per-space geo/map settings — today just the self-hosted tile-server URL the Geo Map studio points
 * MapLibre at. Persisted as {@code geo.toon} in the space's config tree (crash-safe TOON, mirroring
 * {@link BrandingSettings}). {@code null} means "unset — no self-hosted tile server"; on disk an unset
 * field is stored as an empty string and read back as {@code null}.
 *
 * <p>The filename is deliberately not a {@code *_pipeline.toon}-style suffix, so the recursive config
 * discovery (which matches by suffix) never mistakes it for a runnable config.
 */
record GeoSettings(String tileServerUrl) {

    static final GeoSettings EMPTY = new GeoSettings(null);

    /** Write to {@code geo.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tile_server_url", tileServerUrl == null ? "" : tileServerUrl);
        AtomicFiles.write(path, JToon.encode(m).getBytes(StandardCharsets.UTF_8), ".geo-");
    }

    /** Read {@code geo.toon} at {@code path}; missing/unreadable → {@link #EMPTY} (all defaults). */
    static GeoSettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            return new GeoSettings(blankToNull(ToonHelper.opt(m, "tile_server_url", "")));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
