package com.gamma.intelligence.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the repo root (the directory holding {@code docs/}) so the pack's read tools can find the
 * doc/glossary/routes files. Two modes:
 * <ul>
 *   <li><b>Packaged-artifact mode (S9):</b> an explicit {@code -Dinspecto.repo.root} — a deployment that
 *       ships without the source tree alongside it points this at the directory that bundles {@code docs/}.
 *       It wins outright.</li>
 *   <li><b>Checkout mode:</b> otherwise, walk up to 6 levels from the working directory looking for a
 *       directory that has both {@code docs/} and {@code inspecto-ui/} children — needed because those are
 *       siblings of this module's own directory and Maven's per-module working directory (e.g.
 *       {@code inspecto-intelligence/} under the reactor) is not the repo root. Works whether launched from
 *       the repo root (production) or a module directory (Maven test).</li>
 * </ul>
 */
final class RepoPaths {

    /** Packaged-artifact override: the directory that bundles {@code docs/} when no source tree is alongside. */
    private static final String ROOT_PROP = "inspecto.repo.root";
    private static final int MAX_LEVELS = 6;

    private RepoPaths() {
    }

    /** Empty when neither the {@code -Dinspecto.repo.root} override nor a checkout resolves — callers then
     *  fall back to their own defaults (e.g. a relative {@code docs/}). */
    static Optional<Path> root() {
        String override = System.getProperty(ROOT_PROP);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override.trim()).toAbsolutePath().normalize();
            return Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < MAX_LEVELS && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("docs")) && Files.isDirectory(dir.resolve("inspecto-ui"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }
}
