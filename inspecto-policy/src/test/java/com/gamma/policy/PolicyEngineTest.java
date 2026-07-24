package com.gamma.policy;

import com.gamma.control.AccessDecider.Decision;
import com.gamma.control.AccessDecider.Explanation;
import com.gamma.control.AccessDecider.Evaluation;
import com.gamma.control.ComponentAccess;
import com.gamma.control.Roles;
import com.gamma.control.Subject;
import com.gamma.event.EventLog;
import com.sun.net.httpserver.HttpServer;
import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Enterprise PDP's own semantics (ABAC A3), exercised through {@link PolicyEngine#decide} on a
 * genuine {@code HttpExchange} (the JDK type has no public constructor — same throwaway-server style
 * as the security module's tests): deny-overrides combining, target matching (a {@code resourceKinds}
 * target never bites at route level), context binding (A1 subject attributes, held roles,
 * {@code resource.space} defaulting to the bound space), ABSTAIN on no-match/no-doc, and the
 * fail-closed DENY on an unreadable document.
 */
class PolicyEngineTest {

    private static final PolicyEngine ENGINE = new PolicyEngine();

    private static void writePolicies(Path configRoot, List<Map<String, Object>> policies) throws Exception {
        Files.writeString(configRoot.resolve("access-policies.toon"), JToon.encode(Map.of("policies", policies)));
    }

    /** Round-trip one decision through a real exchange, stamped the way {@code ControlApi} +
     *  {@code OidcAuthenticator} would: config root, held roles, bound-space MDC. */
    private static Decision decide(Path configRoot, Set<String> heldRoles, String space, Subject subject,
                                   String action, String resourceKind, Map<String, Object> resource) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Decision> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            if (configRoot != null) ex.setAttribute(Roles.ATTR_CONFIG_ROOT, configRoot);
            if (heldRoles != null) ex.setAttribute(ComponentAccess.ATTR_HELD_ROLES, heldRoles);
            if (space != null) MDC.put(EventLog.SPACE_MDC_KEY, space);
            try {
                result.set(ENGINE.decide(ex, subject, action, "/route/under/test", resourceKind, resource));
            } finally {
                MDC.remove(EventLog.SPACE_MDC_KEY);
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
            done.countDown();
        });
        server.start();
        try {
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/"))
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return result.get();
        } finally {
            server.stop(0);
        }
    }

    /** Same real-exchange round-trip as {@link #decide}, but for the {@link PolicyEngine#explain} dry-run. */
    private static Explanation explain(Path configRoot, Set<String> heldRoles, String space, Subject subject,
                                       String action, String resourceKind, Map<String, Object> resource) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Explanation> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            if (configRoot != null) ex.setAttribute(Roles.ATTR_CONFIG_ROOT, configRoot);
            if (heldRoles != null) ex.setAttribute(ComponentAccess.ATTR_HELD_ROLES, heldRoles);
            if (space != null) MDC.put(EventLog.SPACE_MDC_KEY, space);
            try {
                result.set(ENGINE.explain(ex, subject, action, "/route/under/test", resourceKind, resource));
            } finally {
                MDC.remove(EventLog.SPACE_MDC_KEY);
            }
            ex.sendResponseHeaders(204, -1);
            ex.close();
            done.countDown();
        });
        server.start();
        try {
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/"))
                            .GET().build(), HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return result.get();
        } finally {
            server.stop(0);
        }
    }

    private static Evaluation traceOf(Explanation e, String name) {
        return e.trace().stream().filter(v -> name.equals(v.name())).findFirst().orElse(null);
    }

    private static Subject subject(String id) {
        return new Subject(id, Set.of("canOperateRuns"));
    }

    @Test
    void seededPoliciesExposesTheSpaceIsolationDenies() {
        List<String> names = ENGINE.seededPolicies().stream().map(com.gamma.control.AccessPolicies.Policy::name).toList();
        assertEquals(List.of("space-isolation", "space-isolation-rows"), names);
        assertTrue(ENGINE.seededPolicies().stream().allMatch(com.gamma.control.AccessPolicies.Policy::deny),
                "the seeds are denies");
    }

    @Test
    void explainMatchesDecideWithADenyOverridesTrace(@TempDir Path root) throws Exception {
        writePolicies(root, List.of(
                Map.of("name", "allow-everyone", "effect", "allow"),
                Map.of("name", "freeze-mallory", "effect", "deny", "when", "subject.id == 'mallory'")));

        Explanation ana = explain(root, null, null, subject("ana"), "write", null, Map.of());
        assertEquals(Decision.ALLOW, ana.decision());
        assertEquals("allow-everyone", ana.matchedPolicy());
        assertTrue(traceOf(ana, "allow-everyone").conditionHeld());
        assertFalse(traceOf(ana, "freeze-mallory").conditionHeld(), "ana isn't mallory — condition false");

        Explanation mallory = explain(root, null, null, subject("mallory"), "write", null, Map.of());
        assertEquals(Decision.DENY, mallory.decision(), "deny overrides");
        assertEquals("freeze-mallory", mallory.matchedPolicy());
        Evaluation freeze = traceOf(mallory, "freeze-mallory");
        assertTrue(freeze.targeted() && freeze.conditionHeld());
        assertEquals("authored", freeze.source());
    }

    @Test
    void explainSurfacesSeedSourceAndUnreadableDoc(@TempDir Path root) throws Exception {
        // No authored doc — the engine-resident seeds alone decide, and the trace tags them as seeds.
        Subject visitor = new Subject("bob", Set.of(), null, Map.of("space", "beta"));
        Explanation denied = explain(root, null, "alpha", visitor, "read", null, Map.of());
        assertEquals(Decision.DENY, denied.decision());
        assertEquals("space-isolation", denied.matchedPolicy());
        assertEquals("seed", traceOf(denied, "space-isolation").source());

        Files.writeString(root.resolve("access-policies.toon"), "policies: [ broken");
        Explanation broken = explain(root, null, "alpha", visitor, "read", null, Map.of());
        assertEquals(Decision.DENY, broken.decision());
        assertEquals("<policies-unreadable>", broken.matchedPolicy());
        assertTrue(broken.trace().isEmpty(), "a fail-closed deny needs no per-policy trace");
    }

    @Test
    void noDocAndNoMatchBothAbstain(@TempDir Path root) throws Exception {
        assertEquals(Decision.ABSTAIN, decide(null, null, null, subject("ana"), "write", null, Map.of()),
                "no bound config root — nothing to evaluate");
        assertEquals(Decision.ABSTAIN, decide(root, null, null, subject("ana"), "write", null, Map.of()),
                "no authored doc — ABSTAIN, the existing gates decide");
        writePolicies(root, List.of(Map.of("name", "reads-only", "effect", "deny",
                "target", Map.of("actions", List.of("operate")))));
        assertEquals(Decision.ABSTAIN, decide(root, null, null, subject("ana"), "write", null, Map.of()),
                "target doesn't accept the action — no match");
    }

    @Test
    void unreadableDocDeniesEverythingLoudly(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("access-policies.toon"), "policies: [ broken");
        assertEquals(Decision.DENY, decide(root, null, null, subject("ana"), "read", null, Map.of()),
                "damage is never 'no policies'");
    }

    @Test
    void denyOverridesAllow(@TempDir Path root) throws Exception {
        writePolicies(root, List.of(
                Map.of("name", "allow-everyone", "effect", "allow"),
                Map.of("name", "freeze-mallory", "effect", "deny", "when", "subject.id == 'mallory'")));
        assertEquals(Decision.ALLOW, decide(root, null, null, subject("ana"), "write", null, Map.of()));
        assertEquals(Decision.DENY, decide(root, null, null, subject("mallory"), "write", null, Map.of()),
                "a later deny beats an earlier allow — deny overrides, not first-match");
    }

    @Test
    void resourceKindTargetsNeverBiteAtRouteLevel(@TempDir Path root) throws Exception {
        writePolicies(root, List.of(Map.of("name", "hide-billing-incidents", "effect", "deny",
                "target", Map.of("resourceKinds", List.of("incident")),
                "when", "resource.caseType == 'billing'")));
        assertEquals(Decision.ABSTAIN, decide(root, null, null, subject("ana"), "write", null, Map.of()),
                "route level resolves no resource — a resourceKinds policy must not match");
        assertEquals(Decision.DENY, decide(root, null, null, subject("ana"), "read",
                "incident", Map.of("caseType", "billing")));
        assertEquals(Decision.ABSTAIN, decide(root, null, null, subject("ana"), "read",
                "incident", Map.of("caseType", "fraud")));
        assertEquals(Decision.ABSTAIN, decide(root, null, null, subject("ana"), "read",
                "dataset", Map.of("caseType", "billing")), "other kinds unaffected");
    }

    @Test
    void spaceScopingBindsResourceSpaceToTheBoundSpace(@TempDir Path root) throws Exception {
        // The A4/SPC-5 shape: rows serve from the bound space; the subject's home space is an A1
        // allowlisted claim. A visitor from another space sees nothing.
        writePolicies(root, List.of(Map.of("name", "space-isolation", "effect", "deny",
                "when", "not (resource.space == subject.space)")));
        Subject local = new Subject("ana", Set.of(), null, Map.of("space", "alpha"));
        Subject visitor = new Subject("bob", Set.of(), null, Map.of("space", "beta"));
        assertEquals(Decision.ABSTAIN, decide(root, null, "alpha", local, "read", "incident", Map.of()),
                "resource.space defaulted to the bound space — matches the local subject");
        assertEquals(Decision.DENY, decide(root, null, "alpha", visitor, "read", "incident", Map.of()),
                "cross-space read denied");
        assertEquals(Decision.ABSTAIN, decide(root, null, "alpha", local, "read",
                "incident", Map.of("space", "alpha")), "an explicit resource space is respected");
    }

    @Test
    void seededSpaceIsolationDeniesCrossSpaceSubjects(@TempDir Path root) throws Exception {
        // A4/SPC-5: no authored doc at all — the engine-resident seeds alone isolate tenants.
        Subject local = new Subject("ana", Set.of(), null, Map.of("space", "alpha"));
        Subject visitor = new Subject("bob", Set.of(), null, Map.of("space", "beta"));
        assertEquals(Decision.ABSTAIN, decide(root, null, "alpha", local, "read", null, Map.of()),
                "home-space subject in their own bound space");
        assertEquals(Decision.DENY, decide(root, null, "alpha", visitor, "read", null, Map.of()),
                "cross-space route access denied by the seed");
        assertEquals(Decision.DENY, decide(root, null, "alpha", visitor, "write", null, Map.of()));
        assertEquals(Decision.DENY, decide(root, null, "alpha", local, "read",
                "incident", Map.of("space", "beta")), "a row carrying a foreign explicit space is denied");
        assertEquals(Decision.ABSTAIN, decide(root, null, "alpha", local, "read",
                "incident", Map.of("space", "alpha")));
    }

    @Test
    void seededSpaceIsolationExemptsOperatorsAndUnmappedSubjects(@TempDir Path root) throws Exception {
        Subject operator = new Subject("op", Set.of("canConfigureAccess"), null, Map.of("space", "beta"));
        assertEquals(Decision.ABSTAIN, decide(root, null, "alpha", operator, "write", null, Map.of()),
                "canConfigureAccess is the operator exemption");
        assertEquals(Decision.ABSTAIN, decide(root, null, "alpha", subject("ana"), "read", null, Map.of()),
                "no mapped home-space claim — isolation cannot engage (never a bricked API)");
        // Server-global routes carry no per-space MDC, so the engine binds env.space to the DEFAULT
        // space (EventLog.currentSpaceId never returns null). A subject whose home space IS the
        // default space reaches them; a foreign-space subject is denied there too (strict isolation).
        Subject defaultLocal = new Subject("d", Set.of(), null, Map.of("space", EventLog.DEFAULT_SPACE_ID));
        assertEquals(Decision.ABSTAIN, decide(root, null, null, defaultLocal, "read", null, Map.of()),
                "default-space subject on the (default-bound) server-global surface");
        Subject foreign = new Subject("ana", Set.of(), null, Map.of("space", "alpha"));
        assertEquals(Decision.DENY, decide(root, null, null, foreign, "read", null, Map.of()),
                "a foreign-space subject cannot reach the default-bound server-global surface");
    }

    @Test
    void authoredPolicyOverridesSeedPerName(@TempDir Path root) throws Exception {
        // The Roles.SEED discipline: authoring the seed's name replaces it — an allow replacement
        // disables the deny (a policy allow never bypasses the capability gates anyway).
        writePolicies(root, List.of(
                Map.of("name", "space-isolation", "effect", "allow"),
                Map.of("name", "space-isolation-rows", "effect", "allow")));
        Subject visitor = new Subject("bob", Set.of(), null, Map.of("space", "beta"));
        assertEquals(Decision.ALLOW, decide(root, null, "alpha", visitor, "read", null, Map.of()),
                "seed deny replaced by the authored policy of the same name");
        // ...while an unrelated authored doc leaves the seeds in force.
        writePolicies(root, List.of(Map.of("name", "unrelated", "effect", "allow",
                "when", "subject.id == 'nobody'")));
        assertEquals(Decision.DENY, decide(root, null, "alpha", visitor, "read", null, Map.of()));
    }

    @Test
    void heldRolesAndCapabilitiesBindUnderSubject(@TempDir Path root) throws Exception {
        writePolicies(root, List.of(
                Map.of("name", "contractor-write-freeze", "effect", "deny",
                        "target", Map.of("actions", List.of("write")),
                        "when", "subject.roles contains 'contractor'")));
        assertEquals(Decision.DENY, decide(root, Set.of("contractor", "operations"), null,
                subject("carl"), "write", null, Map.of()));
        assertEquals(Decision.ABSTAIN, decide(root, Set.of("operations"), null,
                subject("olly"), "write", null, Map.of()));
        assertEquals(Decision.ABSTAIN, decide(root, Set.of("contractor"), null,
                subject("carl"), "read", null, Map.of()), "the target keeps reads open");
    }
}
