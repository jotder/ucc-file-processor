package com.gamma.config.spec;

import java.util.Map;

/**
 * Read-only navigation over a decoded config map by <b>dotted path</b>.
 *
 * <p>A config {@code .toon}/JSON decodes to a nested {@code Map<String,Object>}; both
 * {@link FieldSpec#path()} and the {@link CrossFieldRule} predicates address values inside it with
 * a dotted path like {@code "processing.csv_settings.engine"}. This helper centralises that walk so
 * the field-level validator and every cross-field rule read the draft the same way — no temp files,
 * no typed {@code Config} instance required (the whole point of validating an unsaved draft).
 *
 * <p>All accessors are null-tolerant: a path that runs off the end of the map (missing intermediate
 * key, or a scalar where a map was expected) yields {@code null}/the supplied default rather than
 * throwing, because "field absent" is a normal validation input, not an error.
 */
public final class RawConfig {

    private RawConfig() {}

    /** The raw value at {@code dottedPath}, or {@code null} if any segment is missing. */
    public static Object at(Map<String, Object> root, String dottedPath) {
        Object cur = root;
        for (String seg : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(seg);
        }
        return cur;
    }

    /** Whether a non-null, non-blank value exists at {@code dottedPath}. */
    public static boolean present(Map<String, Object> root, String dottedPath) {
        Object v = at(root, dottedPath);
        if (v == null) {
            return false;
        }
        return !(v instanceof String s) || !s.isBlank();
    }

    /** The value at {@code dottedPath} as a string, or {@code null} if absent. */
    public static String str(Map<String, Object> root, String dottedPath) {
        Object v = at(root, dottedPath);
        return v == null ? null : v.toString();
    }

    /** The value at {@code dottedPath} parsed as an int, or {@code def} if absent/unparseable. */
    public static int intOr(Map<String, Object> root, String dottedPath, int def) {
        Object v = at(root, dottedPath);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The value at {@code dottedPath} parsed as a boolean, or {@code def} if absent. */
    public static boolean boolOr(Map<String, Object> root, String dottedPath, boolean def) {
        Object v = at(root, dottedPath);
        return v == null ? def : Boolean.parseBoolean(v.toString().trim());
    }
}
