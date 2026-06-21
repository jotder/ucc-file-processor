package com.gamma.control;

import java.util.List;

/**
 * Alert execution engine routes ({@code /alerts*}, v4.1 B5): read-only listings of recent alerts and
 * the loaded {@code *_alert.toon} rules, plus a manual evaluation sweep. The engine itself is
 * event-driven off the batch bus and lives in the lean core (no agent required); these routes degrade
 * to an empty list when no rules are armed. Extracted verbatim from {@link ControlApi}: identical
 * routes, order, statuses and shapes.
 */
final class AlertRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/alerts", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.recent(ApiContext.parseIntOr(ApiContext.query(e, "limit"), 50)))
                .orElse(List.of()));
        api.get("/alerts/rules", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.rules())
                .orElse(List.of()));
        api.post("/alerts/evaluate", (e, m) -> api.service().alertService()
                .map(a -> (Object) a.evaluateAll())
                .orElseThrow(() -> new ApiException(503,
                        "alert engine not armed (no *_alert.toon rules loaded)")));
    }
}
