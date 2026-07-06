package com.gamma.security;

import com.nimbusds.jwt.JWTClaimsSet;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * claims → Roles → Capabilities (W6, design §8), mirroring
 * {@code docs/superpower/rbac-groundwork.md} §3's role taxonomy 1:1 — this is not a new policy, it is
 * the already-agreed table re-derived server-side instead of the frontend honor system. Reads
 * {@code rolesClaim} as a role-name list (case-insensitive) and unions each role's grants. An
 * unrecognised role name grants nothing (fail-closed — never "everything").
 *
 * <p><b>Out of scope</b> (rbac-groundwork §4 open Q2): case-type/business-function data-scoped grants
 * ("a fraud analyst sees fraud cases") need server-side row filtering per bounded context, not a
 * capability set — deferred, not built here.
 */
final class RoleMapper {
    private RoleMapper() {}

    static final String CAN_AUTHOR_WORKBENCH    = "canAuthorWorkbench";
    static final String CAN_OPERATE_RUNS        = "canOperateRuns";
    static final String CAN_TRIAGE_REQUIREMENTS = "canTriageRequirements";

    static Set<String> capabilitiesFor(JWTClaimsSet claims, String rolesClaim) {
        Set<String> caps = new LinkedHashSet<>();
        for (String role : roles(claims, rolesClaim)) {
            switch (role.toLowerCase(Locale.ROOT)) {
                case "pipeline-developer", "app-developer", "developer" -> caps.add(CAN_AUTHOR_WORKBENCH);
                case "operations", "support" -> caps.add(CAN_OPERATE_RUNS);
                case "power" -> { caps.add(CAN_AUTHOR_WORKBENCH); caps.add(CAN_OPERATE_RUNS); }
                case "super" -> {
                    caps.add(CAN_AUTHOR_WORKBENCH);
                    caps.add(CAN_OPERATE_RUNS);
                    caps.add(CAN_TRIAGE_REQUIREMENTS);
                }
                default -> { /* "business", "admin", or unrecognised — no additional grant */ }
            }
        }
        return caps;
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
