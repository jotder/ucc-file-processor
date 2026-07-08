package com.gamma.intelligence.pack;

import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PageDescriptor;

import java.util.List;
import java.util.Optional;

/**
 * The UI pages a {@code NavigationIntent} may target, derived from
 * {@code inspecto-ui/src/app/app.routes.ts} at construction time ({@link RoutesCatalogLoader}) —
 * plan §1's fast-follow, closed. Falls back to an empty catalog (no {@code NavigationTool}
 * registered) when the route file can't be found, e.g. a standalone deployment without the UI
 * source tree alongside it.
 */
final class InspectoNavigationCatalog implements NavigationCatalog {

    private final List<PageDescriptor> pages = RoutesCatalogLoader.load();

    @Override
    public List<PageDescriptor> pages() {
        return pages;
    }

    @Override
    public Optional<PageDescriptor> find(String pageId) {
        return pages.stream().filter(p -> p.pageId().equals(pageId)).findFirst();
    }
}
