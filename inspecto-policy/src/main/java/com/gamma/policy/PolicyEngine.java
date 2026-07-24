package com.gamma.policy;

import com.gamma.control.AccessDecider;
import com.gamma.control.AccessPolicies;
import com.gamma.control.ComponentAccess;
import com.gamma.control.Subject;
import com.gamma.event.EventLog;
import com.gamma.util.Conditions;
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
 *
 * <p><b>Seeded policies (A4 = SPC-5):</b> {@link #SEED} ships per-tenant space isolation as ordinary
 * policies, resident here (never written to disk) and overlaid <b>per policy name</b> by the authored
 * doc — the {@code Roles.SEED} discipline: author a policy named {@code space-isolation} /
 * {@code space-isolation-rows} to tailor it (an {@code allow} replacement effectively disables it —
 * a policy allow never bypasses the capability gates). The denies only engage for a subject whose
 * {@code space} home-space claim is mapped (A1 {@code attributeClaims}) — a deployment that has not
 * mapped one gets no isolation rather than a bricked API — and exempt {@code canConfigureAccess}
 * holders (the operator escape hatch, same as R3's sharing). {@code env.space} is always bound
 * ({@code EventLog.currentSpaceId} falls back to the default space), so un-prefixed server-global
 * routes bind the default space — only a default-home (or operator) subject reaches them.
 */
public final class PolicyEngine implements AccessDecider {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);

    private static final String EXEMPT =
            "not (subject.capabilities contains 'canConfigureAccess')";

    /** The seeded space-scoping policies (A4 = SPC-5), overlaid per name by the authored doc. */
    static final List<AccessPolicies.Policy> SEED = List.of(
            // Route + row level: a subject with a home space may only address their own bound space.
            seed("space-isolation", "subject.space != null and env.space != null"
                    + " and not (env.space == subject.space) and " + EXEMPT),
            // Rows that carry an explicit space of their own (beyond the bound-space default).
            seed("space-isolation-rows", "subject.space != null and resource.space != null"
                    + " and not (resource.space == subject.space) and " + EXEMPT));

    private static AccessPolicies.Policy seed(String name, String when) {
        return new AccessPolicies.Policy(name, "deny", java.util.Set.of(), java.util.Set.of(),
                when, Conditions.parse(when));
    }

    @Override
    public Decision decide(HttpExchange ex, Subject subject, String action, String route,
                           String resourceKind, Map<String, Object> resource) {
        AccessPolicies.Doc doc = AccessPolicies.effective(ex);
        if (doc.unreadable()) {
            LOG.warn("access-policies doc unreadable — DENY {} {} for '{}' (fail-closed until fixed)",
                    action, route, subject.id());
            ex.setAttribute(AccessDecider.ATTR_MATCHED_POLICY, "<policies-unreadable>");
            return Decision.DENY;
        }
        Map<String, Object> context = context(ex, subject, action, route, resourceKind, resource);
        Decision verdict = Decision.ABSTAIN;
        String matched = null;
        for (AccessPolicies.Policy p : effective(doc.policies())) {
            if (!targets(p, action, resourceKind)) continue;
            if (!p.condition().test(context)) continue;
            if (p.deny()) {   // deny overrides — no later allow can rescue
                ex.setAttribute(AccessDecider.ATTR_MATCHED_POLICY, p.name());
                return Decision.DENY;
            }
            verdict = Decision.ALLOW;
            matched = p.name();
        }
        if (verdict == Decision.ALLOW) ex.setAttribute(AccessDecider.ATTR_MATCHED_POLICY, matched);
        return verdict;
    }

    /** The A4 seeded space-scoping denies — surfaced (read-only) by {@code GET /access/policies} so an
     *  operator sees the built-in denies they never authored (BACKLOG §5 seed visibility). */
    @Override
    public List<AccessPolicies.Policy> seededPolicies() {
        return SEED;
    }

    /** "Why denied?" dry-run (BACKLOG §5): the same deny-overrides evaluation as {@link #decide}, but
     *  it evaluates <em>every</em> effective policy to build a full per-policy trace and never enforces,
     *  stamps, or audits. Matches {@code decide}'s outcome exactly: an unreadable doc denies loudly; the
     *  first matching {@code deny} names the DENY; otherwise the last matching {@code allow} names an
     *  ALLOW; otherwise ABSTAIN. */
    @Override
    public Explanation explain(HttpExchange ex, Subject subject, String action, String route,
                               String resourceKind, Map<String, Object> resource) {
        AccessPolicies.Doc doc = AccessPolicies.effective(ex);
        if (doc.unreadable())
            return new Explanation(Decision.DENY, "<policies-unreadable>", List.of());

        Map<String, Object> context = context(ex, subject, action, route, resourceKind, resource);
        java.util.Set<String> authored = doc.policies().stream()
                .map(AccessPolicies.Policy::name).collect(java.util.stream.Collectors.toSet());
        List<Evaluation> trace = new java.util.ArrayList<>();
        String denyName = null, allowName = null;
        for (AccessPolicies.Policy p : effective(doc.policies())) {
            boolean targeted = targets(p, action, resourceKind);
            boolean held = targeted && p.condition().test(context);
            trace.add(new Evaluation(p.name(), p.effect(),
                    authored.contains(p.name()) ? "authored" : "seed", targeted, held));
            if (held) {
                if (p.deny()) { if (denyName == null) denyName = p.name(); }  // first deny names the DENY
                else allowName = p.name();                                    // last matching allow wins
            }
        }
        if (denyName != null) return new Explanation(Decision.DENY, denyName, trace);
        if (allowName != null) return new Explanation(Decision.ALLOW, allowName, trace);
        return new Explanation(Decision.ABSTAIN, null, trace);
    }

    /** Authored policies overlaid on {@link #SEED} per policy name — an authored policy with a
     *  seed's name replaces that seed; unnamed seeds stay in force. Order-preserving, though the
     *  deny-overrides combining is order-insensitive in outcome. */
    private static List<AccessPolicies.Policy> effective(List<AccessPolicies.Policy> authored) {
        if (authored.isEmpty()) return SEED;
        Map<String, AccessPolicies.Policy> merged = new LinkedHashMap<>();
        for (AccessPolicies.Policy p : SEED) merged.put(p.name(), p);
        for (AccessPolicies.Policy p : authored) merged.put(p.name(), p);
        return List.copyOf(merged.values());
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
