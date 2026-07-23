package com.gamma.policy;

import com.gamma.control.AccessDecider;
import com.gamma.control.AccessPolicies;
import com.gamma.control.ComponentAccess;
import com.gamma.control.Subject;
import com.gamma.event.EventLog;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Enterprise PDP (ABAC A3, {@code docs/superpower/rbac-abac-plan.md} §4): evaluates the bound
 * space's authored Access Policies ({@link AccessPolicies} — already parsed, mtime-fresh) against a
 * {@code subject.* / resource.* / env.*} attribute context assembled per decision. Registered via
 * {@code META-INF/services/com.gamma.control.AccessDecider}; dropping this jar on the classpath is
 * what turns policy evaluation on.
 *
 * <p><b>Combining (plan §2):</b> deny overrides — the first matching {@code deny} wins; otherwise a
 * matching {@code allow} yields ALLOW; otherwise ABSTAIN (the core's existing capability/data-scope/
 * sharing gates decide). A policy matches when its target accepts the action (empty = any) and the
 * resource kind (a {@code resourceKinds} target never matches a route-level call, where no resource
 * is resolved) and its {@code when} condition holds over the context.
 *
 * <p><b>Fail-closed:</b> an unreadable {@code access-policies.toon} (on-disk damage — the PUT route
 * 422s bad documents) denies every decision, loudly, until fixed — never "no policies".
 *
 * <p><b>Context:</b> {@code subject.id/capabilities/dataScopes/roles} + every A1 allowlisted claim
 * flattened under {@code subject.*} ({@code roles} via the authenticator-stamped exchange attribute
 * — the Subject itself stays capabilities-only, guideline 13); {@code env.action/route/space} (the
 * request's bound space); {@code resource.*} = the row's attribute map (row-level only), with
 * {@code resource.space} defaulting to the bound space when the row carries none — resources live
 * in the space that serves them (what A4's seeded space-scoping policies condition on).
 */
public final class PolicyEngine implements AccessDecider {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);

    @Override
    public Decision decide(HttpExchange ex, Subject subject, String action, String route,
                           String resourceKind, Map<String, Object> resource) {
        AccessPolicies.Doc doc = AccessPolicies.effective(ex);
        if (doc.unreadable()) {
            LOG.warn("access-policies doc unreadable — DENY {} {} for '{}' (fail-closed until fixed)",
                    action, route, subject.id());
            return Decision.DENY;
        }
        if (doc.policies().isEmpty()) return Decision.ABSTAIN;
        Map<String, Object> context = context(ex, subject, action, route, resourceKind, resource);
        Decision verdict = Decision.ABSTAIN;
        for (AccessPolicies.Policy p : doc.policies()) {
            if (!targets(p, action, resourceKind)) continue;
            if (!p.condition().test(context)) continue;
            if (p.deny()) return Decision.DENY;   // deny overrides — no later allow can rescue
            verdict = Decision.ALLOW;
        }
        return verdict;
    }

    /** Target match: empty dimensions are unconstrained; a {@code resourceKinds} target requires a
     *  resolved resource (row level) — it never bites on a route-level call. */
    private static boolean targets(AccessPolicies.Policy p, String action, String resourceKind) {
        if (!p.actions().isEmpty() && !p.actions().contains(action)) return false;
        return p.resourceKinds().isEmpty()
                || (resourceKind != null && p.resourceKinds().contains(resourceKind));
    }

    private static Map<String, Object> context(HttpExchange ex, Subject subject, String action,
                                               String route, String resourceKind, Map<String, Object> resource) {
        Map<String, Object> s = new LinkedHashMap<>(subject.attributes());   // A1 allowlisted claims
        s.put("id", subject.id());
        s.put("capabilities", List.copyOf(subject.capabilities()));
        if (subject.scoped()) s.put("dataScopes", List.copyOf(subject.dataScopes()));
        s.put("roles", heldRoles(ex));

        String space = EventLog.currentSpaceId();
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("action", action);
        env.put("route", route);
        if (space != null && !space.isBlank()) env.put("space", space);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("subject", s);
        ctx.put("env", env);
        if (resourceKind != null) {
            Map<String, Object> r = new LinkedHashMap<>(resource == null ? Map.of() : resource);
            r.putIfAbsent("kind", resourceKind);
            if (space != null && !space.isBlank()) r.putIfAbsent("space", space);
            ctx.put("resource", r);
        }
        return ctx;
    }

    /** The recognised role names, via the same server-internal channel R3's sharing uses — the
     *  authenticator-stamped {@link ComponentAccess#ATTR_HELD_ROLES} exchange attribute (the Subject
     *  itself never carries role names). Empty when unstamped. */
    private static List<String> heldRoles(HttpExchange ex) {
        return ex.getAttribute(ComponentAccess.ATTR_HELD_ROLES) instanceof Collection<?> c
                ? c.stream().map(String::valueOf).toList() : List.of();
    }
}
