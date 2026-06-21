package com.gamma.control;

import com.gamma.report.ReportService;
import com.gamma.service.EnrichmentService;
import com.sun.net.httpserver.HttpExchange;

import java.util.regex.Matcher;

/**
 * Stage-2 enrichment routes ({@code /enrichment*}, v2.9.0): the per-job run audit, output lineage,
 * and the run-audit rollup report. Extracted verbatim from {@link ControlApi}: identical routes,
 * order, statuses and shapes.
 */
final class EnrichmentRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/enrichment", (e, m) -> enrichment(api).views());
        api.get("/enrichment/([^/]+)/runs", (e, m) -> enrichment(api).runs(enrichJob(api, m)));
        api.get("/enrichment/([^/]+)/lineage", (e, m) ->
                enrichment(api).lineage(enrichJob(api, m), ApiContext.query(e, "runId")));
        api.get("/enrichment/([^/]+)/report", (e, m) ->
                api.service().reports().enrichmentReport(enrichJob(api, m), window(e)));
    }

    /** The enrichment service, or a 404 when no enrichment jobs are registered. */
    private EnrichmentService enrichment(ApiContext api) {
        return api.service().enrichmentService()
                .orElseThrow(() -> new ApiException(404, "no enrichment jobs registered"));
    }

    /** Resolve a path-named enrichment job to its name, 404 when it is not registered. */
    private String enrichJob(ApiContext api, Matcher m) {
        String n = ApiContext.name(m);
        if (enrichment(api).config(n).isEmpty())
            throw new ApiException(404, "no enrichment job named '" + n + "'");
        return n;
    }

    /** Build a report {@link ReportService.Window} from {@code ?from=&to=}. */
    private static ReportService.Window window(HttpExchange ex) {
        return ReportService.Window.of(ApiContext.query(ex, "from"), ApiContext.query(ex, "to"));
    }
}
