package com.gamma.control;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The declared capability → route table (RBAC R4, {@code docs/superpower/rbac-abac-plan.md} §3):
 * every {@code ApiContext.withCapability} gate in the control plane, as data. This is the audit
 * surface the scattered per-route gates lacked — {@code CapabilityManifestTest} asserts this table
 * and the actual registration sites match <b>exactly</b> (both directions), so adding, removing, or
 * re-gating a route without updating the manifest fails the build. It also feeds
 * {@link Roles#KNOWN_CAPABILITIES} (the 422 vocabulary for {@code roles.toon} and Access-Catalog
 * action nodes) and, under R2, the Access Catalog's action-node binding.
 *
 * <p>Keep entries grouped by route class, in registration order — the test reports diffs by entry.
 */
final class CapabilityManifest {
    private CapabilityManifest() {}

    record Entry(String method, String pattern, String capability) {}

    static final List<Entry> ENTRIES = List.of(
            // AccessRoutes
            new Entry("PUT", "/access/roles", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("PUT", "/access/policies", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("PUT", "/access/catalog", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("PUT", "/access/profiles/([^/]+)", Roles.CAN_CONFIGURE_ACCESS),
            new Entry("DELETE", "/access/profiles/([^/]+)", Roles.CAN_CONFIGURE_ACCESS),
            // AcquisitionRoutes
            new Entry("POST", "/collectors/([^/]+)/notify", Roles.CAN_OPERATE_RUNS),
            // AlertRoutes
            new Entry("POST", "/alerts/rules", Roles.CAN_AUTHOR_ALERT_RULES),
            new Entry("PUT", "/alerts/rules/([^/]+)", Roles.CAN_AUTHOR_ALERT_RULES),
            new Entry("DELETE", "/alerts/rules/([^/]+)", Roles.CAN_AUTHOR_ALERT_RULES),
            // BiRoutes
            new Entry("POST", "/bi/templates/([^/]+)/apply", Roles.CAN_AUTHOR_WORKBENCH),
            // BundleRoutes
            new Entry("POST", "/bundle/import", Roles.CAN_AUTHOR_WORKBENCH),
            // ComponentRoutes
            new Entry("POST", "/components/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/components/([^/]+)/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/components/([^/]+)/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/components/([^/]+)/([^/]+)/versions/([^/]+)/restore", Roles.CAN_AUTHOR_WORKBENCH),
            // ConfigRoutes
            new Entry("POST", "/config/write", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/config/([^/]+)/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // ConnectionRoutes
            new Entry("POST", "/connections", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("PUT", "/connections/([^/]+)", Roles.CAN_ONBOARD_CONNECTIONS),
            new Entry("DELETE", "/connections/([^/]+)", Roles.CAN_ONBOARD_CONNECTIONS),
            // DecisionRoutes
            new Entry("POST", "/decision-rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/decision-rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/decision-rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/decision-rules/([^/]+)/simulate", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/decision-rules/([^/]+)/apply", Roles.CAN_OPERATE_RUNS),
            // EnrichmentRoutes
            new Entry("POST", "/enrichment", Roles.CAN_AUTHOR_WORKBENCH),
            // ExchangeRoutes
            new Entry("POST", "/exchange/offers", Roles.CAN_OFFER_DATASETS),
            new Entry("POST", "/exchange/refresh", Roles.CAN_OFFER_DATASETS),
            new Entry("POST", "/exchange/requests", Roles.CAN_REQUEST_SHARES),
            new Entry("POST", "/exchange/grants/([^/]+)/(approve|deny|revoke)", Roles.CAN_APPROVE_SHARES),
            new Entry("POST", "/exchange/grants/([^/]+)/pin", Roles.CAN_REQUEST_SHARES),
            new Entry("POST", "/exchange/grants/([^/]+)/expiry", Roles.CAN_APPROVE_SHARES),
            // ExpectationRoutes
            new Entry("POST", "/expectations", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/expectations/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/expectations/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // JobRoutes
            new Entry("POST", "/jobs", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/jobs/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/jobs/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/jobs/packs/rescan", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/([^/]+)/enable", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/([^/]+)/disable", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/jobs/([^/]+)/reschedule", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/jobs/([^/]+)/trigger", Roles.CAN_OPERATE_RUNS),
            // NavRoutes
            new Entry("PUT", "/nav/menus", Roles.CAN_AUTHOR_WORKBENCH),
            // NotificationRoutes
            new Entry("POST", "/notifications/channels", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/notifications/channels/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/notifications/channels/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // ObjectRoutes
            new Entry("POST", "/cases/rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/cases/rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            // PipelineRoutes
            new Entry("POST", "/pipelines/authored", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/pipelines/authored/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/pipelines/authored/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/authored/([^/]+)/nodes", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/authored/([^/]+)/edges", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/pipelines/authored/([^/]+)/trigger", Roles.CAN_OPERATE_RUNS),
            // QueueRoutes
            new Entry("POST", "/queues", Roles.CAN_AUTHOR_WORKBENCH),
            // RequirementRoutes
            new Entry("POST", "/requirements/([^/]+)/decision", Roles.CAN_TRIAGE_REQUIREMENTS),
            new Entry("POST", "/requirements/([^/]+)/deliver", Roles.CAN_TRIAGE_REQUIREMENTS),
            // RunRoutes
            new Entry("POST", "/runs", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/runs/([^/]+)/trigger", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/pause", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/resume", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/runs/([^/]+)/reprocess", Roles.CAN_OPERATE_RUNS),
            new Entry("POST", "/trigger", Roles.CAN_OPERATE_RUNS),
            // SettingsRoutes
            new Entry("PUT", "/settings/branding", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/settings/geo", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("PUT", "/config/icon-map", Roles.CAN_AUTHOR_WORKBENCH),
            // ShareRoutes
            new Entry("POST", "/dashboards/([^/]+)/share", Roles.CAN_AUTHOR_WORKBENCH),
            // TagRoutes
            new Entry("POST", "/tags", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("POST", "/tags/rules", Roles.CAN_AUTHOR_WORKBENCH),
            new Entry("DELETE", "/tags/rules/([^/]+)", Roles.CAN_AUTHOR_WORKBENCH));

    /** The capability vocabulary — every capability some route gate demands. */
    static Set<String> capabilities() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : ENTRIES) out.add(e.capability());
        return out;
    }
}
