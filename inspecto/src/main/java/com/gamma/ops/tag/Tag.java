package com.gamma.ops.tag;

import com.gamma.config.io.ConfigCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A user-created tag (GLOSSARY §9) — a free-form label attached to {@link com.gamma.ops.OperationalObject}s
 * (Incidents/Cases) for cross-cutting grouping, the mail metaphor's "label". Objects carry their tags as a
 * comma-separated {@code tags} attribute ({@link com.gamma.ops.ObjectService#ATTR_TAGS}); this record is the
 * registry entry that makes a tag exist independently of any tagged object.
 *
 * <p>Authored as a {@code *_tag.toon} (a {@code tag { … }} block) loaded at bootstrap, or created at runtime
 * via {@code POST /tags} (which persists the same file under the write root). Applied manually or by a
 * {@link TagRule}.
 *
 * @since 5.0.0
 */
@com.gamma.api.PublicApi(since = "5.0.0")
public record Tag(String name, long createdAt) {

    public Tag {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tag name is required");
        name = name.trim();
        if (name.contains(",")) throw new IllegalArgumentException("tag names may not contain commas");
    }

    /** JSON-ready view (stable key order) — backs the {@code /tags} API. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("createdAt", createdAt);
        return m;
    }

    /** Load a {@code *_tag.toon} (a {@code tag { … }} block). */
    @SuppressWarnings("unchecked")
    public static Tag load(Path path) throws IOException {
        Map<String, Object> root = ConfigCodec.toMap(Files.readString(path));
        Object t = root.get("tag");
        if (!(t instanceof Map)) throw new IllegalArgumentException(path + " has no 'tag' block");
        return fromMap((Map<String, Object>) t);
    }

    /** Parse + validate from a decoded {@code tag { … }} map. */
    public static Tag fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("missing 'tag' block");
        Object created = m.get("createdAt");
        long at = 0;
        if (created != null) {
            try {
                at = Long.parseLong(created.toString().trim());
            } catch (NumberFormatException ignored) {
                // createdAt is informational — a bad value degrades to 0, never rejects the tag
            }
        }
        Object name = m.get("name");
        return new Tag(name == null ? null : name.toString(), at > 0 ? at : System.currentTimeMillis());
    }
}
