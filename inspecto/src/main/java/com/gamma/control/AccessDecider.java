package com.gamma.control;

import com.gamma.api.PublicApi;
import com.sun.net.httpserver.HttpExchange;

import java.util.Map;

/**
 * The PDP seam (ABAC A3, {@code docs/superpower/rbac-abac-plan.md} §2): an edition-supplied decider
 * consulted at the two PEPs — the route-level authorize stage (after authentication, before the
 * handler) and the row-level {@link RowScope} filter. Discovered via
 * {@code META-INF/services/com.gamma.control.AccessDecider} exactly like {@link Authenticator}:
 * the core ships none, Personal/Standard classpaths resolve empty and behave byte-identically; the
 * Enterprise {@code inspecto-policy} module registers its policy engine.
 *
 * <p>The core stays auth-free — it only threads the request facts through and enforces the verdict:
 * {@code DENY} is a 403 at the route level and invisibility (404/filtered, the SEC-7d contract) at
 * the row level. {@code ALLOW} and {@code ABSTAIN} both fall through to the existing gates
 * (capability checks, data scopes, sharing) — an explicit policy allow never bypasses a capability
 * gate (defense in depth; a deliberate tightening of the plan's §2 combining order, where policy
 * allow preceded the capability check).
 */
@PublicApi(since = "5.0.0")
public interface AccessDecider {

    enum Decision { ALLOW, DENY, ABSTAIN }

    /**
     * Exchange attribute (ABAC A5): after {@link #decide} returns {@code ALLOW} or {@code DENY}, the
     * decider MAY stamp the matched Access Policy's name here so the core audit stage can name it in
     * the {@code access.denied}/{@code access.granted} entry. Absent on {@code ABSTAIN}. The core
     * clears it before every {@code decide} and reads it immediately after, so a decider that never
     * stamps simply audits an unnamed decision. Value is a {@link String}.
     */
    String ATTR_MATCHED_POLICY = "com.gamma.control.AccessDecider.matchedPolicy";

    /**
     * Decide one access. Route-level calls pass {@code resourceKind = null} and an empty
     * {@code resource} (nothing is resolved yet — policies targeting {@code resourceKinds} must not
     * match); row-level calls pass the resolved resource's kind and attribute map.
     *
     * @param ex           the exchange (carries the bound space's config root and the held-roles
     *                     attribute; implementations must not write the response)
     * @param subject      the authenticated caller — never null (the core skips the decider when no
     *                     Subject is attached: Personal and the public probe surface)
     * @param action       {@code read} | {@code write} | {@code operate}
     * @param route        the effective route path
     * @param resourceKind the resolved resource's kind (lower-case), or null at route level
     * @param resource     the resolved resource's attributes (empty at route level)
     */
    Decision decide(HttpExchange ex, Subject subject, String action, String route,
                    String resourceKind, Map<String, Object> resource);
}
