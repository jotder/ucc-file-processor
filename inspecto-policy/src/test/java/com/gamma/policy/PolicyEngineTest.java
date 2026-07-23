package com.gamma.policy;

import com.gamma.control.AccessDecider.Decision;
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

    private static Subject subject(String id) {
        return new Subject(id, Set.of("canOperateRuns"));
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
