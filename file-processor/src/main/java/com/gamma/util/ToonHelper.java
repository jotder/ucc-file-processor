package com.gamma.util;

import dev.toonformat.jtoon.JToon;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Shared helpers for reading pipeline {@code .toon} configuration files.
 *
 * <p>These tiny methods were previously copy-pasted independently into every class
 * that loaded or validated a toon — {@link FileOrganizer}, {@link FileBackup},
 * {@link TarArranger}, {@link TarInboxPreparer}, and others.  This class is the
 * single canonical home.
 */
public final class ToonHelper {

    private ToonHelper() {}

    // ── loading ───────────────────────────────────────────────────────────────

    /**
     * Parse and return the top-level map from a {@code .toon} file at {@code path}.
     *
     * @throws FileNotFoundException if the file does not exist
     * @throws IOException           on any I/O or parse failure
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p))
            throw new FileNotFoundException("Toon file not found: " + path);
        return (Map<String, Object>)
                JToon.decode(Files.readString(p, StandardCharsets.UTF_8));
    }

    // ── section access ────────────────────────────────────────────────────────

    /**
     * Return the value at {@code key} cast to {@code Map<String,Object>}.
     * Throws {@link IllegalArgumentException} when absent or of the wrong type.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> requireSection(Map<String, Object> toon, String key) {
        Object val = toon.get(key);
        if (!(val instanceof Map))
            throw new IllegalArgumentException(
                    "Pipeline toon is missing required section '" + key + "'");
        return (Map<String, Object>) val;
    }

    /**
     * Return a required, non-blank String key from a toon section.
     * Throws {@link IllegalArgumentException} when the key is absent or blank.
     */
    public static String require(Map<String, Object> section, String key, String sectionName) {
        Object val = section.get(key);
        if (val == null || val.toString().isBlank())
            throw new IllegalArgumentException(
                    "Missing required key '" + key + "' in toon section '" + sectionName + "'");
        return val.toString();
    }

    /**
     * Return an optional String key from a toon section,
     * falling back to {@code defaultVal} when absent or blank.
     */
    public static String opt(Map<String, Object> section, String key, String defaultVal) {
        Object val = section.get(key);
        return (val != null && !val.toString().isBlank()) ? val.toString() : defaultVal;
    }

    // ── base_dirs ─────────────────────────────────────────────────────────────

    /**
     * Parse {@code base_dirs} from a toon section into an unmodifiable list of
     * absolute, normalised {@link Path}s.
     *
     * <p>{@code base_dirs} may be a JToon array ({@code List<String>}) or a
     * comma-separated scalar string — both forms are accepted.
     *
     * @throws IllegalArgumentException if the key is absent or resolves to an empty list
     */
    @SuppressWarnings("unchecked")
    public static List<Path> parseBaseDirs(Map<String, Object> section) {
        Object val = section.get("base_dirs");
        if (val == null)
            throw new IllegalArgumentException("Missing 'base_dirs' in toon section");

        List<Path> result = new ArrayList<>();
        Iterable<?> items = (val instanceof List)
                ? (List<?>) val
                : Arrays.asList(val.toString().split(","));

        for (Object item : items) {
            String s = item.toString().trim();
            if (!s.isEmpty())
                result.add(Paths.get(s).toAbsolutePath().normalize());
        }
        if (result.isEmpty())
            throw new IllegalArgumentException("'base_dirs' is empty");

        return Collections.unmodifiableList(result);
    }
}
