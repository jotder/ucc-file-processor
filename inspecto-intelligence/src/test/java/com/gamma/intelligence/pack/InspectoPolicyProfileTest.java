package com.gamma.intelligence.pack;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage for the host-role → platform-Role/capability mapping (AGT-5 P0). */
class InspectoPolicyProfileTest {

    private final InspectoPolicyProfile profile = new InspectoPolicyProfile();

    @Test
    void mapsKnownRolesCaseInsensitivelyAndTrimmed() {
        assertEquals(Role.ADMIN, profile.mapRole("admin"));
        assertEquals(Role.ANALYST, profile.mapRole("  Analyst "));
        assertEquals(Role.SUPPORT, profile.mapRole("SUPPORT"));
    }

    @Test
    void mapRoleIsTotalDefaultingToLeastPrivilege() {
        assertEquals(Role.USER, profile.mapRole("nonsense"));
        assertEquals(Role.USER, profile.mapRole(""));
        assertEquals(Role.USER, profile.mapRole(null), "null host role ⇒ least-privileged USER");
    }

    @Test
    void grantsAreReadOnlyForNonAdminTiers() {
        assertEquals(Set.of(Capability.READ_DOCS, Capability.READ_METADATA), profile.grants(Role.USER));

        var analyst = profile.grants(Role.ANALYST);
        assertTrue(analyst.contains(Capability.READ_SCHEMA));
        assertTrue(analyst.contains(Capability.RUN_SQL_READONLY));

        assertTrue(profile.grants(Role.SUPPORT).contains(Capability.INVESTIGATE));
    }

    @Test
    void adminGetsTheFullCapabilitySet() {
        assertEquals(EnumSet.allOf(Capability.class), profile.grants(Role.ADMIN));
    }
}
