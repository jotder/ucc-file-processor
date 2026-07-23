package com.gamma.security;

import com.gamma.control.Roles;
import com.nimbusds.jwt.JWTClaimsSet;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * claims → Roles → Capabilities (W6, design §8). Since RBAC R1 the role → grant table is no longer
 * hardcoded here: it is resolved per request from {@link Roles#effective} — the bound space's
 * authored {@code roles.toon} overlaid on the shipped seed (which carries the original
 * {@code rbac-groundwork.md} §3 taxonomy, corrected to the full route capability vocabulary). Reads
 * {@code rolesClaim} as a role-name list (case-insensitive) and unions each role's grants. An
 * unrecognised role name grants nothing (fail-closed — never "everything").
 *
 * <p><b>Data scopes (SEC-7d, closes rbac-groundwork §4 Q2):</b> {@link #dataScopesFor} resolves the
 * case-type/business-function visibility grants ("a fraud analyst sees fraud cases") that
 * {@code ObjectRoutes} row-filters on. Three sources feed it: a {@code data_scopes} string-list
 * claim, role names of the form {@code case:<scope>} (e.g. {@code case:fraud}), and — new with R1 —
 * a matched role's authored {@code dataScopes}. When none is present the subject is <b>unscoped</b>
 * ({@code null}) — every plain role keeps full visibility, exactly the pre-scoping behaviour.
 */
final class RoleMapper {
    private RoleMapper() {}

    static Set<String> capabilitiesFor(JWTClaimsSet claims, String rolesClaim, Map<String, Roles.Def> defs) {
        Set<String> caps = new LinkedHashSet<>();
        for (String role : roles(claims, rolesClaim)) {
            Roles.Def def = defs.get(role.toLowerCase(Locale.ROOT));
            if (def != null) caps.addAll(def.capabilities());
        }
        return caps;
    }

    /**
     * The caller's data-visibility scopes (SEC-7d), or {@code null} when unscoped. Union of the
     * {@code data_scopes} string-list claim, any {@code case:<scope>} role names, and any matched
     * role's authored {@code dataScopes} (all lower-cased). Distinguishes "no scoping sources at all"
     * ({@code null} — sees everything) from an explicit empty list ({@code []} — sees only untyped
     * objects, fail-closed).
     */
    static Set<String> dataScopesFor(JWTClaimsSet claims, String rolesClaim, Map<String, Roles.Def> defs) {
        Set<String> scopes = new LinkedHashSet<>();
        boolean scoped = false;
        try {
            List<String> direct = claims.getStringListClaim("data_scopes");
            if (direct != null) {
                scoped = true;
                for (String s : direct) if (s != null && !s.isBlank()) scopes.add(s.trim().toLowerCase(Locale.ROOT));
            }
        } catch (ParseException ignored) {
            // not a string list — treated as absent
        }
        for (String role : roles(claims, rolesClaim)) {
            String r = role.toLowerCase(Locale.ROOT);
            if (r.startsWith("case:")) {
                scoped = true;
                String s = r.substring("case:".length()).trim();
                if (!s.isEmpty()) scopes.add(s);
            }
            Roles.Def def = defs.get(r);
            if (def != null && def.dataScopes() != null) {
                scoped = true;
                scopes.addAll(def.dataScopes());
            }
        }
        return scoped ? scopes : null;
    }

    /** The role-name list from {@code rolesClaim}, or (when that claim is absent/not a string list and
     *  the caller asked for the default {@code "roles"}) Keycloak's default nesting
     *  {@code realm_access.roles}. Empty when neither shape is present. */
    private static List<String> roles(JWTClaimsSet claims, String rolesClaim) {
        try {
            List<String> direct = claims.getStringListClaim(rolesClaim);
            if (direct != null) return direct;
        } catch (ParseException ignored) {
            // not a string list under this claim name — fall through to the Keycloak nesting below
        }
        if ("roles".equals(rolesClaim) && claims.getClaim("realm_access") instanceof Map<?, ?> realm
                && realm.get("roles") instanceof List<?> nested) {
            List<String> out = new ArrayList<>();
            for (Object o : nested) out.add(String.valueOf(o));
            return out;
        }
        return List.of();
    }
}
