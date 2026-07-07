package com.gamma.intelligence.pack;

import com.eoiagent.app.PolicyProfile;
import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Maps Inspecto host roles onto the platform {@link Role} and grants capabilities per tier.
 * {@code mapRole} is total (unknown → least-privileged {@code USER}). P0 grants read-only
 * capabilities to every non-admin tier — mutating capabilities are gated off entirely by
 * {@link InspectoPackConfig} (autonomy ladder L0) regardless of what a role is granted here.
 */
final class InspectoPolicyProfile implements PolicyProfile {

    @Override
    public Role mapRole(String hostRole) {
        return switch (hostRole == null ? "" : hostRole.trim().toLowerCase(Locale.ROOT)) {
            case "admin" -> Role.ADMIN;
            case "analyst" -> Role.ANALYST;
            case "support" -> Role.SUPPORT;
            default -> Role.USER; // total → least-privileged
        };
    }

    @Override
    public Set<Capability> grants(Role role) {
        return switch (role) {
            case USER -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA);
            case ANALYST -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA,
                    Capability.READ_SCHEMA, Capability.RUN_SQL_READONLY);
            case SUPPORT -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA, Capability.INVESTIGATE);
            case ADMIN -> EnumSet.allOf(Capability.class); // full set; mutating still gated by profile/config
        };
    }
}
