package com.gamma.config.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the raw text of a configuration resource by reference.
 *
 * <p>This is the seam that decouples config <em>parsing</em> from config <em>storage</em>. The
 * default {@link #filesystem()} loader reads a file path, but {@link #ofMap(Map)} serves
 * in-memory content — which is what lets a draft be validated straight from a REST body, and lets
 * loader tests run without touching the filesystem. Both are provided as factory methods on this
 * one-method interface (they were previously two single-class implementations).
 *
 * <p>A {@code ref} is whatever the implementation understands (a file path, a logical key). The
 * loader's only job is text retrieval; decoding and validation happen in {@link ConfigLoader}.
 */
public interface ResourceLoader {

    /** The synthetic ref used by {@link #ofDraft(String)} for a single anonymous draft. */
    String DRAFT = "<draft>";

    /**
     * Return the full text of the resource at {@code ref}.
     *
     * @throws IOException if the resource is missing or cannot be read
     */
    String load(String ref) throws IOException;

    /**
     * The default loader: reads a resource as a UTF-8 file from the filesystem, matching
     * {@code ToonHelper.load}'s "file not found" contract so it is a drop-in for path-based loading.
     */
    static ResourceLoader filesystem() {
        return ref -> {
            Path p = Paths.get(ref);
            if (!Files.exists(p)) {
                throw new FileNotFoundException("Config resource not found: " + ref);
            }
            return Files.readString(p, StandardCharsets.UTF_8);
        };
    }

    /**
     * An in-memory loader backed by a {@code ref → content} map — for validating unsaved drafts
     * from a REST body and keeping loader tests free of temp-file plumbing.
     */
    static ResourceLoader ofMap(Map<String, String> resources) {
        Map<String, String> copy = new HashMap<>(resources == null ? Map.of() : resources);
        return ref -> {
            String content = copy.get(ref);
            if (content == null) {
                throw new FileNotFoundException("No in-memory config resource for ref: " + ref);
            }
            return content;
        };
    }

    /** A loader holding one draft under the {@link #DRAFT} ref. */
    static ResourceLoader ofDraft(String content) {
        return ofMap(Map.of(DRAFT, content));
    }
}
