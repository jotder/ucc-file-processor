package com.gamma.control;

import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RBAC R2 grant resolution ({@link AccessGrants}): nearest-ancestor inheritance over the Access
 * Catalog, union-of-access across a subject's roles, and the fail-closed unreadable-document
 * behaviour — pure filesystem, no HTTP (the route/authenticator halves are covered by
 * {@code ControlApiAccessTest}/{@code OidcAuthenticatorTest}).
 */
class AccessGrantsTest {

    /** menu "ops-menu" [deny-target] → action "runs.trigger" (canOperateRuns)
     *                                → action "runs.author" (canAuthorWorkbench, explicit allow escape)
     *  action "alerts.author" (canAuthorAlertRules) at root. */
    private static void writeCatalog(Path root) throws Exception {
        Map<String, Object> catalog = Map.of("version", 1, "nodes", List.of(
                Map.of("id", "ops-menu", "label", "Ops", "kind", "menu", "children", List.of(
                        Map.of("id", "runs.trigger", "label", "Trigger", "kind", "action",
                                "capability", Roles.CAN_OPERATE_RUNS),
                        Map.of("id", "runs.author", "label", "Author", "kind", "action",
                                "capability", Roles.CAN_AUTHOR_WORKBENCH))),
                Map.of("id", "alerts.author", "label", "Alerts", "kind", "action",
                        "capability", Roles.CAN_AUTHOR_ALERT_RULES)));
        Path dir = Files.createDirectories(root.resolve("registry").resolve("access-catalog"));
        Files.writeString(dir.resolve("catalog.toon"), JToon.encode(catalog));
    }

    private static void writeProfile(Path root, String role, Map<String, String> grants) throws Exception {
        Path dir = Files.createDirectories(root.resolve("registry").resolve("access-profiles"));
        Files.writeString(dir.resolve("role-" + role + ".toon"), JToon.encode(Map.of(
                "subjectType", "role", "subjectId", role, "label", role, "grants", grants)));
    }

    @Test
    void nothingToEnforceResolvesEmpty(@TempDir Path root) throws Exception {
        assertTrue(AccessGrants.deniedCapabilities((Path) null, List.of("business")).isEmpty());
        assertTrue(AccessGrants.deniedCapabilities(root, List.of()).isEmpty());
        // a held role without a saved profile allows everywhere — union short-circuit
        writeCatalog(root);
        writeProfile(root, "business", Map.of("alerts.author", "deny"));
        assertTrue(AccessGrants.deniedCapabilities(root, List.of("business", "developer")).isEmpty(),
                "unprofiled 'developer' allows everywhere, so the union denies nothing");
    }

    @Test
    void nearestAncestorCascadesAndExplicitChildAllowEscapes(@TempDir Path root) throws Exception {
        writeCatalog(root);
        writeProfile(root, "business", Map.of("ops-menu", "deny", "runs.author", "allow"));
        Set<String> denied = AccessGrants.deniedCapabilities(root, List.of("business"));
        assertTrue(denied.contains(Roles.CAN_OPERATE_RUNS), "menu deny cascades to the child action");
        assertFalse(denied.contains(Roles.CAN_AUTHOR_WORKBENCH), "explicit child allow overrides the ancestor deny");
        assertFalse(denied.contains(Roles.CAN_AUTHOR_ALERT_RULES), "untouched root node keeps the default allow");
    }

    @Test
    void unionAcrossRolesNeverReducesAccess(@TempDir Path root) throws Exception {
        writeCatalog(root);
        writeProfile(root, "business", Map.of("ops-menu", "deny"));
        writeProfile(root, "operations", Map.of());   // profiled, no explicit grants — default allow
        assertEquals(Set.of(Roles.CAN_OPERATE_RUNS, Roles.CAN_AUTHOR_WORKBENCH),
                AccessGrants.deniedCapabilities(root, List.of("business")));
        assertTrue(AccessGrants.deniedCapabilities(root, List.of("business", "operations")).isEmpty(),
                "any held role resolving allow wins — more roles never means less access");
    }

    @Test
    void unreadableDocumentsFailClosed(@TempDir Path root) throws Exception {
        writeCatalog(root);
        Path profiles = Files.createDirectories(root.resolve("registry").resolve("access-profiles"));
        Files.writeString(profiles.resolve("role-business.toon"), "grants: [ not valid");
        assertEquals(Set.of(Roles.CAN_OPERATE_RUNS, Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES),
                AccessGrants.deniedCapabilities(root, List.of("business")),
                "an unreadable profile contributes no allows — every cataloged capability denied");

        Files.writeString(root.resolve("registry/access-catalog/catalog.toon"), "nodes: [ broken");
        writeProfile(root, "operations", Map.of());
        assertEquals(Roles.KNOWN_CAPABILITIES, AccessGrants.deniedCapabilities(root, List.of("operations")),
                "an unreadable catalog with every role profiled denies everything, loudly");
    }

    @Test
    void profileEditAppliesWithoutRestart(@TempDir Path root) throws Exception {
        writeCatalog(root);
        writeProfile(root, "business", Map.of("alerts.author", "deny"));
        assertEquals(Set.of(Roles.CAN_AUTHOR_ALERT_RULES),
                AccessGrants.deniedCapabilities(root, List.of("business")));
        writeProfile(root, "business", Map.of("alerts.author", "deny", "ops-menu", "deny"));
        assertEquals(Set.of(Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_OPERATE_RUNS, Roles.CAN_AUTHOR_WORKBENCH),
                AccessGrants.deniedCapabilities(root, List.of("business")), "edit picked up on the next call");
    }

    @Test
    void claimSuppliedRoleNamesNeverEscapeThePathJail(@TempDir Path root) throws Exception {
        writeCatalog(root);
        writeProfile(root, "business", Map.of("ops-menu", "deny"));
        assertTrue(AccessGrants.deniedCapabilities(root, List.of("business", "../../evil")).isEmpty(),
                "a non-storable role name resolves as unprofiled (allow everywhere), never as a path");
    }
}
