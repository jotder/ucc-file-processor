package com.gamma.pipeline;

import com.gamma.api.PublicApi;

/**
 * Edge relationship vocabulary for the {@link PipelineGraph}. An edge's {@code rel} is a plain
 * string so the set stays open (a {@code transform.route} emits operator-defined branch
 * names), but the well-known control + split relationships are named here so the lift, the
 * validator and the visualiser agree on spelling.
 *
 * <p>The default is {@link #DATA} — the record-set flowing downstream. <b>Control</b>
 * relationships route a batch on an outcome (failure / unmatched / gap / on_commit);
 * <b>split</b> relationships are the diverted side of a record operator (dropped / invalid /
 * duplicate). Operator-defined content routes use the {@link #ROUTE_PREFIX} ({@code route:emea}).
 *
 * <p>See {@code docs/flow-graph-design.md} §3.2 (edges) and §15 (the capability inventory that
 * fixed the split-relationship set).
 */
@PublicApi(since = "4.3.0")
public final class PipelineRel {

    private PipelineRel() {}

    /** The normal downstream record-set edge (the default when an edge omits {@code rel}). */
    public static final String DATA = "data";

    // ── control relationships (route a batch on an outcome) ──────────────────────
    /** Terminal batch success. */
    public static final String SUCCESS = "success";
    /** Terminal batch failure (→ quarantine / dead-letter). */
    public static final String FAILURE = "failure";
    /** Parser could not match a schema/column-count (→ quarantine or a fallback parser). */
    public static final String UNMATCHED = "unmatched";
    /** A sequence gap was detected (→ {@code gap}/{@code alert} node). */
    public static final String GAP = "gap";
    /** A batch committed (→ {@code enrichment} / a downstream flow trigger). Cross-flow only. */
    public static final String ON_COMMIT = "on_commit";

    // ── split relationships (the diverted side of a record operator) ─────────────
    /** Records dropped by {@code transform.filter}. */
    public static final String DROPPED = "dropped";
    /** Records failing {@code transform.validate}. */
    public static final String INVALID = "invalid";
    /** Records dropped by {@code transform.dedup.*}. */
    public static final String DUPLICATE = "duplicate";

    /** Prefix for operator-defined content-routing branches, e.g. {@code route:emea}. */
    public static final String ROUTE_PREFIX = "route:";

    /** Build a named content-route relationship: {@code route("emea")} → {@code "route:emea"}. */
    public static String route(String key) {
        return ROUTE_PREFIX + key;
    }

    /** Whether {@code rel} is a named content-route ({@code route:*}). */
    public static boolean isRoute(String rel) {
        return rel != null && rel.startsWith(ROUTE_PREFIX) && rel.length() > ROUTE_PREFIX.length();
    }

    /** The branch key of a {@code route:*} relationship, or {@code null} if {@code rel} is not a route. */
    public static String routeKey(String rel) {
        return isRoute(rel) ? rel.substring(ROUTE_PREFIX.length()) : null;
    }
}
