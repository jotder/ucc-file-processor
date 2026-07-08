package com.gamma.intelligence.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the repo root from the working directory — needed because {@code docs/} and
 * {@code inspecto-ui/} are siblings of this module's own directory, and Maven's per-module working
 * directory (e.g. {@code inspecto-intelligence/} under the reactor) is not the repo root. Walks up
 * to 6 levels looking for a directory that has both {@code docs/} and {@code inspecto-ui/} as
 * children — true for a normal checkout, whether launched from the repo root (production) or from
 * a module directory (Maven test).
 */
final class RepoPaths {

    private static final int MAX_LEVELS = 6;

    private RepoPaths() {
    }

    /** Empty when packaged/run standalone without the docs/UI source trees alongside it. */
    static Optional<Path> root() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < MAX_LEVELS && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("docs")) && Files.isDirectory(dir.resolve("inspecto-ui"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }
}
