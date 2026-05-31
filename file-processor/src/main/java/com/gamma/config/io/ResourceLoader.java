package com.gamma.config.io;

import java.io.IOException;

/**
 * Loads the raw text of a configuration resource by reference.
 *
 * <p>This is the seam that decouples config <em>parsing</em> from config <em>storage</em>. The
 * default {@link FilesystemResourceLoader} reads a file path, but {@link MapResourceLoader} serves
 * in-memory content — which is what lets a draft be validated straight from a REST body, and lets
 * loader tests run without touching the filesystem.
 *
 * <p>A {@code ref} is whatever the implementation understands (a file path, a logical key). The
 * loader's only job is text retrieval; decoding and validation happen in {@link ConfigLoader}.
 */
public interface ResourceLoader {

    /**
     * Return the full text of the resource at {@code ref}.
     *
     * @throws IOException if the resource is missing or cannot be read
     */
    String load(String ref) throws IOException;
}
