package com.gamma.config.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The default {@link ResourceLoader}: reads a resource as a UTF-8 file from the filesystem,
 * matching the behaviour of {@code ToonHelper.load} (the same "file not found" contract) so this
 * loader is a drop-in for the existing path-based loading.
 */
public final class FilesystemResourceLoader implements ResourceLoader {

    @Override
    public String load(String ref) throws IOException {
        Path p = Paths.get(ref);
        if (!Files.exists(p)) {
            throw new FileNotFoundException("Config resource not found: " + ref);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
