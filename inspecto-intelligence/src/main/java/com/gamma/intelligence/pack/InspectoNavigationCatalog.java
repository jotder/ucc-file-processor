package com.gamma.intelligence.pack;

import com.eoiagent.app.NavigationCatalog;
import com.eoiagent.app.PageDescriptor;
import com.eoiagent.app.ParamSpec;

import java.util.List;
import java.util.Optional;

/**
 * The UI pages a {@code NavigationIntent} may target — page ids mirror the top-level routes in
 * {@code inspecto-ui/src/app/app.routes.ts}. P0 covers a first slice by hand; deriving the full
 * catalog automatically from the route table is a fast-follow (plan §1).
 */
final class InspectoNavigationCatalog implements NavigationCatalog {

    private static final List<PageDescriptor> PAGES = List.of(
            new PageDescriptor("overview", "Dashboard", "Operational overview and KPIs", List.of()),
            new PageDescriptor("pipelines", "Pipelines", "ETL pipeline configuration and runs",
                    List.of(new ParamSpec("pipelineId", "string", false, "pipeline id to focus"))),
            new PageDescriptor("catalog", "Metadata Catalog", "Datasets, schemas and lineage",
                    List.of(new ParamSpec("datasetId", "string", false, "dataset id to focus"))),
            new PageDescriptor("incidents", "Incidents", "Data-quality incidents",
                    List.of(new ParamSpec("incidentId", "string", false, "incident id to focus"))),
            new PageDescriptor("requirements", "Requirements", "Requirements triage inbox", List.of()),
            new PageDescriptor("kpi-reports", "KPI Reports", "Measures, widgets and dashboards", List.of()));

    @Override
    public List<PageDescriptor> pages() {
        return PAGES;
    }

    @Override
    public Optional<PageDescriptor> find(String pageId) {
        return PAGES.stream().filter(p -> p.pageId().equals(pageId)).findFirst();
    }
}
