package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Component sharing RBAC (R3, {@code docs/superpower/rbac-abac-plan.md} §3): decides a request's
 * access to one registry component from the component's optional sharing envelope —
 * {@code owner: <subject id>} plus {@code shares: [{subjectType: role|user, subjectId, access:
 * view|edit}]} — additive TOON keys inside the component content, absent on every existing
 * component.
 *
 * <p><b>Semantics.</b> A component without a {@code shares} key behaves exactly as today
 * ({@code owner} alone is provenance, stamped at create time, not a restriction). Once
 * {@code shares} is present (even empty) the component is restricted: the owner and holders of
 * {@code canConfigureAccess} (the governance escape hatch — an orphaned owner id must never brick a
 * component) have full access, a matching share grants {@code view} or {@code edit}, and everyone
 * else gets a 404 indistinguishable from absence (the SEC-7d contract — no existence leak).
 * Deleting a restricted component is owner-only; changing the envelope itself is owner-only on any
 * component that declares an owner. With no {@link Subject} attached (Personal edition, fail-open)
 * everything is allowed, unchanged.
 *
 * <p><b>Role matching.</b> The {@link Subject} is capabilities-only by design (guideline 13 — role
 * names never leave the authenticator or reach the SPA), so {@code subjectType: role} shares match
 * against {@link #ATTR_HELD_ROLES}, the lowercased <em>recognised</em> (table-backed) role names the
 * security module's authenticator stamps on the exchange at token-validation time — a server-internal
 * seam like {@link Roles#ATTR_CONFIG_ROOT}, never serialized to a response. An {@link Authenticator}
 * that does not stamp it simply matches no role shares (fail-closed); {@code subjectType: user}
 * shares match the IdP {@code sub} carried as {@link Subject#id()} (§8 Q4: opaque id, no user
 * directory needed).
 */
public final class ComponentAccess {
    private ComponentAccess() {}

    /** Exchange attribute carrying the authenticated subject's recognised role names (a lowercased
     *  {@code Set<String>}), stamped by the security module's {@link Authenticator} alongside the
     *  {@link Subject}. Server-internal only. Absent ⇒ no {@code subjectType: role} share matches. */
    public static final String ATTR_HELD_ROLES = "inspecto.access.heldRoles";

    static final String OWNER = "owner";
    static final String SHARES = "shares";

    private static final int MAX_SHARES = 100;

    /** Access levels: NONE hides the component (404), VIEW reads, EDIT writes content,
     *  OWN additionally deletes and manages the envelope. */
    private static final int NONE = 0, VIEW = 1, EDIT = 2, OWN = 3;

    /** Whether this request may see {@code content} at all (list filtering + read routes). */
    static boolean canView(HttpExchange ex, Map<String, Object> content) {
        return level(ex, content) >= VIEW;
    }

    /** 404 (indistinguishable from absence) unless the request may view the component. */
    static void requireView(HttpExchange ex, String type, String id, Map<String, Object> content) {
        if (!canView(ex, content))
            throw new ApiException(404, "no " + type + " component '" + id + "'");
    }

    /** {@link #requireView} then 403 unless the request may edit (owner, edit share, or unrestricted). */
    static void requireEdit(HttpExchange ex, String type, String id, Map<String, Object> content) {
        requireView(ex, type, id, content);
        if (level(ex, content) < EDIT)
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    type + " component '" + id + "' is shared view-only");
    }

    /** Delete gate: unrestricted components keep today's behavior; a restricted (shared) component
     *  may only be deleted by its owner / an access admin — an edit share is not ownership. */
    static void requireDelete(HttpExchange ex, String type, String id, Map<String, Object> content) {
        requireView(ex, type, id, content);
        if (content.containsKey(SHARES) && level(ex, content) < OWN)
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "only the owner may delete shared " + type + " component '" + id + "'");
    }

    /** Create-path shaping: validate the envelope (422) and stamp {@code owner} from the
     *  authenticated subject when absent (provenance; no restriction until shares are added). */
    static Map<String, Object> onCreate(HttpExchange ex, Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<>(content);
        if (ex.getAttribute(ApiContext.ATTR_SUBJECT) instanceof Subject s && !out.containsKey(OWNER))
            out.put(OWNER, s.id());
        validate(out);
        return out;
    }

    /**
     * Update-path shaping: enforce edit access against the <em>current</em> envelope, carry the
     * envelope forward when the body omits it (a plain content save must never strip protection),
     * validate (422), and reject envelope changes from anyone but the owner / an access admin (403).
     * Returns the content to persist.
     */
    static Map<String, Object> onUpdate(HttpExchange ex, String type, String id,
                                        Map<String, Object> current, Map<String, Object> incoming) {
        requireEdit(ex, type, id, current);
        Map<String, Object> merged = new LinkedHashMap<>(incoming);
        if (!merged.containsKey(OWNER) && current.containsKey(OWNER)) merged.put(OWNER, current.get(OWNER));
        if (!merged.containsKey(SHARES) && current.containsKey(SHARES)) merged.put(SHARES, current.get(SHARES));
        validate(merged);
        boolean envelopeChanged = !Objects.equals(current.get(OWNER), merged.get(OWNER))
                || !Objects.equals(current.get(SHARES), merged.get(SHARES));
        if (envelopeChanged && !ownsEnvelope(ex, current))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                    "only the owner may change 'owner'/'shares' on " + type + " component '" + id + "'");
        return merged;
    }

    // ── decision ─────────────────────────────────────────────────────────────────

    private static int level(HttpExchange ex, Map<String, Object> content) {
        if (!(ex.getAttribute(ApiContext.ATTR_SUBJECT) instanceof Subject s)) return OWN;  // Personal: fail-open
        String owner = str(content.get(OWNER));
        boolean ownerMatch = !owner.isEmpty() && owner.equals(s.id());
        boolean admin = s.capabilities().contains(Roles.CAN_CONFIGURE_ACCESS);
        if (!content.containsKey(SHARES)) return ownerMatch || admin ? OWN : EDIT;  // unrestricted
        if (ownerMatch || admin) return OWN;
        int best = NONE;
        Set<String> held = heldRoles(ex);
        if (content.get(SHARES) instanceof List<?> shares) {
            for (Object o : shares) {
                if (!(o instanceof Map<?, ?> share)) continue;   // malformed entry grants nothing
                String subjectType = str(share.get("subjectType"));
                String subjectId = str(share.get("subjectId"));
                boolean match = ("user".equals(subjectType) && subjectId.equals(s.id()))
                        || ("role".equals(subjectType) && held.contains(subjectId.toLowerCase(Locale.ROOT)));
                if (match) best = Math.max(best, "edit".equals(str(share.get("access"))) ? EDIT : VIEW);
            }
        }
        return best;
    }

    /** May this request manage the envelope? True with no subject, for an access admin, for the
     *  declared owner — and for anyone when no owner is declared yet (first claim on a legacy doc). */
    private static boolean ownsEnvelope(HttpExchange ex, Map<String, Object> current) {
        if (!(ex.getAttribute(ApiContext.ATTR_SUBJECT) instanceof Subject s)) return true;
        if (s.capabilities().contains(Roles.CAN_CONFIGURE_ACCESS)) return true;
        String owner = str(current.get(OWNER));
        return owner.isEmpty() || owner.equals(s.id());
    }

    private static Set<String> heldRoles(HttpExchange ex) {
        if (!(ex.getAttribute(ATTR_HELD_ROLES) instanceof Set<?> roles)) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Object r : roles) if (r != null) out.add(String.valueOf(r).toLowerCase(Locale.ROOT));
        return out;
    }

    // ── validation (write-path 422s — stored malformed entries just grant nothing) ──

    /** Validate the sharing envelope inside {@code content}; throws {@link ApiException} 422. */
    static void validate(Map<String, Object> content) {
        if (content.containsKey(OWNER) && str(content.get(OWNER)).isEmpty())
            throw new ApiException(422, "'owner' must be a non-blank subject id");
        if (!content.containsKey(SHARES)) return;
        if (!(content.get(SHARES) instanceof List<?> shares))
            throw new ApiException(422, "'shares' must be a list of {subjectType, subjectId, access}");
        if (shares.size() > MAX_SHARES)
            throw new ApiException(422, "too many shares (max " + MAX_SHARES + ")");
        for (Object o : shares) {
            if (!(o instanceof Map<?, ?> share))
                throw new ApiException(422, "every share must be an object {subjectType, subjectId, access}");
            String subjectType = str(share.get("subjectType"));
            if (!"role".equals(subjectType) && !"user".equals(subjectType))
                throw new ApiException(422, "share 'subjectType' must be 'role' or 'user', got '" + subjectType + "'");
            if (str(share.get("subjectId")).isEmpty())
                throw new ApiException(422, "share 'subjectId' is required");
            String access = str(share.get("access"));
            if (!"view".equals(access) && !"edit".equals(access))
                throw new ApiException(422, "share 'access' must be 'view' or 'edit', got '" + access + "'");
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
