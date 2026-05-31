package com.gamma.config.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory {@link ResourceLoader} backed by a {@code ref → content} map.
 *
 * <p>Two uses: validating an unsaved <b>draft</b> from a REST body (register the draft text under a
 * synthetic ref, then load/validate it with no file ever written), and keeping loader tests free of
 * temp-file plumbing. A common convenience is the single-resource form via {@link #of(String)}.
 */
public final class MapResourceLoader implements ResourceLoader {

    /** The synthetic ref used by {@link #of(String)} for a single anonymous draft. */
    public static final String DRAFT = "<draft>";

    private final Map<String, String> resources;

    public MapResourceLoader(Map<String, String> resources) {
        this.resources = new HashMap<>(resources == null ? Map.of() : resources);
    }

    /** A loader holding one draft under the {@link #DRAFT} ref. */
    public static MapResourceLoader of(String content) {
        return new MapResourceLoader(Map.of(DRAFT, content));
    }

    @Override
    public String load(String ref) throws IOException {
        String content = resources.get(ref);
        if (content == null) {
            throw new FileNotFoundException("No in-memory config resource for ref: " + ref);
        }
        return content;
    }
}
