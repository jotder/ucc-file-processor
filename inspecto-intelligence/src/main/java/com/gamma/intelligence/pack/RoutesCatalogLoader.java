package com.gamma.intelligence.pack;

import com.eoiagent.app.PageDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives the navigation catalog from {@code inspecto-ui/src/app/app.routes.ts} — the plan's own
 * "fast-follow" (§1: "NavigationCatalog (route map from {@code app.routes.ts})"). Only top-level
 * {@code loadChildren} entries count as real feature pages (excludes {@code redirectTo} aliases
 * like {@code dashboard}→{@code overview} and non-feature routes like {@code sign-in}, which use
 * {@code loadComponent} instead).
 *
 * <p>Route params aren't declared in {@code app.routes.ts} itself, so every derived
 * {@link PageDescriptor} has an empty param list — a known limitation (a page component would need
 * to declare its own required params for this to improve). Missing/unreadable file → an empty
 * catalog (never throws — the pack must still assemble offline).
 */
final class RoutesCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(RoutesCatalogLoader.class);
    private static final Pattern ROUTE_LINE = Pattern.compile("path:\\s*'([a-z0-9-]+)'.*loadChildren:");

    private RoutesCatalogLoader() {
    }

    static List<PageDescriptor> load() {
        java.util.Optional<Path> routesFile = RepoPaths.root()
                .map(r -> r.resolve("inspecto-ui").resolve("src").resolve("app").resolve("app.routes.ts"));
        if (routesFile.isEmpty()) {
            log.warn("Could not locate inspecto-ui/src/app/app.routes.ts (repo root not found); navigation catalog is empty");
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(routesFile.get());
        } catch (IOException e) {
            log.warn("Could not read {}: {}", routesFile.get(), e.getMessage());
            return List.of();
        }
        List<PageDescriptor> pages = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = ROUTE_LINE.matcher(line);
            if (m.find() && seen.add(m.group(1))) {
                String pageId = m.group(1);
                pages.add(new PageDescriptor(pageId, titleCase(pageId),
                        "Inspecto " + titleCase(pageId) + " page", List.of()));
            }
        }
        return List.copyOf(pages);
    }

    private static String titleCase(String kebab) {
        String[] words = kebab.split("-");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (!out.isEmpty()) out.append(' ');
            out.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        return out.toString();
    }
}
