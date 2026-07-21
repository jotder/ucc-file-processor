package com.gamma.ops.rca;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A root-cause-analysis template — an ordered list of named sections an investigator fills in, the
 * "RCA templates as authored {@code .toon}" of the Operational Intelligence Platform (Phase 4
 * follow-up). Per the requirement's "configuration over custom code" principle a template is authored
 * as an {@code *_rca.toon} ({@code rca { name, sections[…] }}) and parsed with the same
 * {@link ConfigCodec} the rest of the platform uses; {@link com.gamma.ops.ObjectService#applyRca} seeds
 * a case with one {@link com.gamma.ops.note.NoteKind#COMMENT} per section, giving the operator a
 * structured skeleton to complete.
 *
 * @since 4.6.0
 */
@com.gamma.api.PublicApi(since = "4.6.0")
public record RcaTemplate(String name, List<String> sections) {

    /** Canonical constructor — name required, sections trimmed and non-empty. */
    public RcaTemplate {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("rca template name is required");
        name = name.trim();
        List<String> cleaned = new ArrayList<>();
        if (sections != null) for (String s : sections) if (s != null && !s.isBlank()) cleaned.add(s.trim());
        if (cleaned.isEmpty()) throw new IllegalArgumentException("rca template needs at least one section");
        sections = List.copyOf(cleaned);
    }

    /** Load an {@code *_rca.toon} (an {@code rca { … }} block). */
    @SuppressWarnings("unchecked")
    public static RcaTemplate load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object rca = root.get("rca");
        if (!(rca instanceof Map)) throw new IllegalArgumentException(path + " has no 'rca' block");
        return fromMap((Map<String, Object>) rca);
    }

    /** Parse + validate from a decoded {@code rca { … }} map (or the request body of {@code POST …/rca}). */
    public static RcaTemplate fromMap(Map<String, Object> rca) {
        if (rca == null) throw new IllegalArgumentException("missing 'rca' block");
        String name = str(rca.get("name"));
        List<String> sections = new ArrayList<>();
        Object secs = rca.get("sections");
        if (secs instanceof List<?> list) for (Object o : list) { String v = str(o); if (v != null) sections.add(v); }
        return new RcaTemplate(name, sections);
    }

    /** JSON-ready view (stable key order). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("sections", sections);
        return m;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
