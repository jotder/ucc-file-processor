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
 * Per-space configurable processor-icon map — keyed by a processor <em>type</em> string ({@code parser.dsv})
 * or a <em>category</em> ({@code PARSE}), each mapping to a {@link Rule} of a glyph name (from the UI's curated
 * GLYPH_LIBRARY) plus a colour swatch. The type entry wins over the category entry, and anything unmapped falls
 * back to the built-in per-kind glyph — all resolved client-side; the backend only persists the map.
 *
 * <p>Stored as {@code icon-map.toon} in the space's config tree (crash-safe TOON), mirroring
 * {@link BrandingSettings}. The filename is deliberately not a {@code *_pipeline.toon}-style suffix, so the
 * recursive config discovery (which matches by suffix) never mistakes it for a runnable config.
 */
record IconMapSettings(Map<String, Rule> rules) {

    static final IconMapSettings EMPTY = new IconMapSettings(Map.of());

    /** One icon rule: a glyph name + a colour swatch. */
    record Rule(String glyph, String color) {}

    /** Write to {@code icon-map.toon} at {@code path} (canonical TOON, crash-safe). */
    void write(Path path) throws IOException {
        AtomicFiles.write(path, JToon.encode(toWire()).getBytes(StandardCharsets.UTF_8), ".icon-map-");
    }

    /** Read {@code icon-map.toon} at {@code path}; missing/unreadable → {@link #EMPTY}. A malformed entry
     *  (missing glyph/color) is skipped rather than failing the whole read. */
    @SuppressWarnings("unchecked")
    static IconMapSettings read(Path path) {
        if (!Files.exists(path)) return EMPTY;
        try {
            Map<String, Object> m = ToonHelper.load(path.toString());
            Map<String, Rule> rules = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> sub)) continue;
                String glyph = str(((Map<String, Object>) sub).get("glyph"));
                String color = str(((Map<String, Object>) sub).get("color"));
                if (glyph != null && color != null) rules.put(e.getKey(), new Rule(glyph, color));
            }
            return new IconMapSettings(rules);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /** The wire shape the UI's {@code IconMapService} expects: {@code { "<type>": {glyph, color}, … }}. */
    Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<>();
        rules.forEach((key, r) -> {
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("glyph", r.glyph());
            sub.put("color", r.color());
            m.put(key, sub);
        });
        return m;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
