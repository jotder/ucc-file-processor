package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.service.CollectorService;
import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Enterprise edition end-to-end (ABAC A3): with {@code inspecto-policy} on the classpath the
 * core's {@code ServiceLoader} seam discovers {@code PolicyEngine} on its own — nothing here forces
 * a decider — and authored Access Policies bite at both PEPs over real HTTP: a route-level deny is a
 * 403 before the handler, a row-level deny is the SEC-7d 404/filtered contract, an unreadable doc
 * denies every authenticated request (loudly), and with no doc at all every route behaves exactly as
 * on Standard. Lives in {@code com.gamma.control} for the {@link Authenticators#forTest} seam (the
 * same package-split the core's own control tests use).
 */
class ControlApiPolicyEnforcementTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer <id>} → an authenticated Subject with full capabilities; an id suffixed
     *  {@code :contractor} additionally carries the A1 attribute {@code employment=contractor}. */
    private static final Authenticator FAKE_AUTH = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        String id = auth.substring(7);
        Map<String, Object> attrs = id.endsWith(":contractor")
                ? Map.of("employment", "contractor") : Map.of();
        return Optional.of(new Subject(id, Set.of("canOperateRuns", "canConfigureAccess"), null, attrs));
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("assist.write.root");
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, Path writeRoot) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Path writeRoot = Files.createDirectories(dir.resolve("cfg"));
        System.setProperty("assist.write.root", writeRoot.toString());
        Authenticators.forTest(FAKE_AUTH);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), writeRoot);
    }

    private static void writePolicies(Ctx c, List<Map<String, Object>> policies) throws Exception {
        Files.writeString(c.writeRoot().resolve("access-policies.toon"),
                JToon.encode(Map.of("policies", policies)));
    }

    @Test
    void routeLevelDenyBitesBeforeTheCapabilityGate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // authored over HTTP — the A2 route is itself the arrangement
            assertEquals(200, send(c.port, "PUT", "/access/policies", """
                    {"policies":[{"name":"contractor-write-freeze","effect":"deny",
                      "target":{"actions":["write","operate"]},
                      "when":"subject.employment == 'contractor'"}]}""", "root").statusCode());

            HttpResponse<String> denied = send(c.port, "PUT", "/access/roles", "{\"roles\":[]}", "carl:contractor");
            assertEquals(403, denied.statusCode(), denied.body());
            assertTrue(denied.body().contains("denied by access policy"),
                    "the policy denies even though the Subject holds canConfigureAccess: " + denied.body());

            assertEquals(200, send(c.port, "GET", "/objects", null, "carl:contractor").statusCode(),
                    "the target keeps the contractor's reads open");
            assertEquals(200, send(c.port, "PUT", "/access/roles", "{\"roles\":[]}", "root").statusCode(),
                    "unmatched subjects write as before");
        }
    }

    @Test
    void rowLevelDenyFiltersListsAndHidesByIdSec7d(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            writePolicies(c, List.of(Map.of("name", "hide-billing-incidents", "effect", "deny",
                    "target", Map.of("resourceKinds", List.of("incident")),
                    "when", "resource.caseType == 'billing'")));
            var objects = c.svc().objects();
            OperationalObject fraud = objects.open(ObjectType.INCIDENT, "sim swap", "d", "HIGH", null, null, null,
                    "corr", Map.of("caseType", "fraud"));
            OperationalObject billing = objects.open(ObjectType.INCIDENT, "rating drift", "d", "HIGH", null, null, null,
                    "corr", Map.of("caseType", "billing"));

            List<String> ids = JSON.readTree(send(c.port, "GET", "/objects?type=INCIDENT", null, "ana").body())
                    .findValuesAsText("id");
            assertTrue(ids.contains(fraud.id()));
            assertFalse(ids.contains(billing.id()), "policy-denied row filtered from the list");
            assertEquals(404, send(c.port, "GET", "/objects/" + billing.id(), null, "ana").statusCode(),
                    "by-id: indistinguishable from absence");
            assertEquals(200, send(c.port, "GET", "/objects/" + fraud.id(), null, "ana").statusCode());
            assertEquals(200, send(c.port, "GET", "/access/policies", null, "ana").statusCode(),
                    "a resourceKinds policy never bites at route level");
        }
    }

    @Test
    void unreadableDocDeniesEveryAuthenticatedRequest(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Files.writeString(c.writeRoot().resolve("access-policies.toon"), "policies: [ broken");
            assertEquals(403, send(c.port, "GET", "/objects", null, "ana").statusCode(),
                    "fail-closed: damage denies loudly, never 'no policies'");
            assertEquals(200, send(c.port, "GET", "/health", null, null).statusCode(),
                    "the public probe surface stays reachable for diagnosis");
            // repair on disk (the PUT route is itself denied while broken — deliberate) → service restored
            writePolicies(c, List.of());
            assertEquals(200, send(c.port, "GET", "/objects", null, "ana").statusCode());
        }
    }

    @Test
    void withoutAnyDocEveryRouteBehavesAsOnStandard(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "GET", "/objects", null, "ana").statusCode());
            assertEquals(200, send(c.port, "PUT", "/access/roles", "{\"roles\":[]}", "ana").statusCode());
            assertEquals(401, send(c.port, "GET", "/objects", null, null).statusCode(),
                    "authentication is still the security module's job");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String subject) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (subject != null) b.header("Authorization", "Bearer " + subject);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
