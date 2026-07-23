package com.gamma.control;

import com.gamma.api.PublicApi;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;

/**
 * The row-level PEP (ABAC A3): generalizes {@code ObjectRoutes}' SEC-7d visibility filter — "an
 * out-of-scope row is invisible, indistinguishable from absence" — to any policy-scoped list/by-id
 * endpoint, driven by the edition's {@link AccessDecider}. A host route builds the resolved row's
 * attribute map (its {@code kind}, {@code id}, and domain attributes) and filters/404s on
 * {@link #visible}. With no decider on the classpath (Personal/Standard) every row is visible and
 * only the pre-existing scoping (data scopes, sharing) applies — byte-identical behaviour.
 *
 * <p>Only {@code DENY} hides a row; {@code ALLOW}/{@code ABSTAIN} leave the existing filters in
 * charge. The action is always {@code read} — row-level writes go through their route's own
 * route-level authorize + capability gates first.
 *
 * <p>A row-level {@code DENY} is recorded to the audit trail with the matched policy name (ABAC A5,
 * {@code access.denied} targeting the row's kind/id). A row-level {@code ALLOW} is <em>not</em>
 * audited: unlike the coarse route-level allow it fires per surviving row, so auditing it would
 * flood the log on every list read — a deliberate scope limit (plan §4 A5).
 */
@PublicApi(since = "5.0.0")
public final class RowScope {
    private RowScope() {}

    /** Whether the authenticated caller may see this resolved row. */
    public static boolean visible(HttpExchange ex, String resourceKind, Map<String, Object> resource) {
        AccessDecider d = AccessDeciders.active().orElse(null);
        if (d == null) return true;
        if (!(ex.getAttribute(ApiContext.ATTR_SUBJECT) instanceof Subject s)) return true;
        ex.setAttribute(AccessDecider.ATTR_MATCHED_POLICY, null);   // clear stale; the decider re-stamps
        String route = ex.getRequestURI().getPath();
        if (d.decide(ex, s, "read", route, resourceKind, resource) != AccessDecider.Decision.DENY)
            return true;
        String policy = ex.getAttribute(AccessDecider.ATTR_MATCHED_POLICY) instanceof String p ? p : null;
        String id = resource.get("id") == null ? null : String.valueOf(resource.get("id"));
        AuditTrail.policyDecision(ex, false, "read", route, resourceKind, id, policy);
        return false;
    }
}
