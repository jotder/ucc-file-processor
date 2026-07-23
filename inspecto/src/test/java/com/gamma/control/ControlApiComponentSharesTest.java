package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Component sharing RBAC (R3, {@code docs/superpower/rbac-abac-plan.md} §3) over real HTTP: the
 * additive {@code owner} + {@code shares} envelope on registry components — owner stamping at
 * create, list filtering, the 404 existence-hiding contract for shared-away subjects (read AND
 * mutate), view-vs-edit shares (user and role subjects), owner-only envelope changes and deletes,
 * the {@code canConfigureAccess} escape hatch, envelope carry-forward on plain saves, and the
 * fail-open Personal path (no {@link Authenticator} ⇒ envelopes are inert). Uses the
 * {@link Authenticators#forTest} seam, restored in teardown; role shares match via
 * {@link ComponentAccess#ATTR_HELD_ROLES}, stamped exactly like the security module does.
 */
class ControlApiComponentSharesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** alice/bob = developers, olly = operations, root = access admin. All may author. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) return Optional.empty();
        return switch (auth.substring(7)) {
            case "alice" -> subject(ex, "alice", Set.of("canAuthorWorkbench"), Set.of("developer"));
            case "bob"   -> subject(ex, "bob", Set.of("canAuthorWorkbench"), Set.of("developer"));
            case "olly"  -> subject(ex, "olly", Set.of("canAuthorWorkbench"), Set.of("operations"));
            case "root"  -> subject(ex, "root", Set.of("canAuthorWorkbench", "canConfigureAccess"), Set.of("admin"));
            default      -> Optional.empty();
        };
    };

    private static Optional<Subject> subject(HttpExchange ex, String id, Set<String> caps, Set<String> roles) {
        ex.setAttribute(ComponentAccess.ATTR_HELD_ROLES, roles);   // what OidcAuthenticator stamps (R3)
        return Optional.of(new Subject(id, caps));
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, boolean authenticated) throws Exception {
        Authenticators.forTest(authenticated ? FAKE : null);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        String prior = System.getProperty("assist.write.root");
        System.setProperty("assist.write.root", dir.resolve("wr").toString());
        try {
            ControlApi api = new ControlApi(svc, 0);   // captures the write root at construction
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            if (prior != null) System.setProperty("assist.write.root", prior);
            else System.clearProperty("assist.write.root");
        }
    }

    // ── envelope basics ─────────────────────────────────────────────────────────────

    @Test
    void createStampsOwnerAndUnsharedComponentsBehaveAsToday(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            HttpResponse<String> created = send(c.port, "POST", "/components/dataset",
                    "{\"id\":\"cdr\",\"label\":\"CDRs\"}", "alice");
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("alice", json(created).get("content").get("owner").asText(),
                    "owner stamped from the authenticated subject");

            // owner alone is provenance, not a restriction: bob still reads, edits and lists it
            assertEquals(200, send(c.port, "GET", "/components/dataset/cdr", null, "bob").statusCode());
            assertEquals(200, send(c.port, "PUT", "/components/dataset/cdr",
                    "{\"label\":\"CDR feed\"}", "bob").statusCode());
            assertEquals(1, json(send(c.port, "GET", "/components/dataset", null, "bob")).size());
            // and bob's plain save did not strip the envelope
            assertEquals("alice", json(send(c.port, "GET", "/components/dataset/cdr", null, "alice"))
                    .get("content").get("owner").asText(), "owner carried forward through bob's save");
        }
    }

    @Test
    void malformedSharesAre422OnWrite(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(422, send(c.port, "POST", "/components/dataset",
                    "{\"id\":\"d1\",\"shares\":[{\"subjectType\":\"group\",\"subjectId\":\"x\",\"access\":\"view\"}]}",
                    "alice").statusCode(), "unknown subjectType");
            assertEquals(422, send(c.port, "POST", "/components/dataset",
                    "{\"id\":\"d1\",\"shares\":[{\"subjectType\":\"user\",\"subjectId\":\"bob\",\"access\":\"admin\"}]}",
                    "alice").statusCode(), "unknown access");
            assertEquals(422, send(c.port, "POST", "/components/dataset",
                    "{\"id\":\"d1\",\"shares\":\"bob\"}", "alice").statusCode(), "shares must be a list");
        }
    }

    // ── visibility + the 404 contract ───────────────────────────────────────────────

    @Test
    void sharedAwaySubjectGets404OnReadMutateAndAFilteredList(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(200, send(c.port, "POST", "/components/widget",
                    "{\"id\":\"w1\",\"vizType\":\"bar\",\"shares\":[{\"subjectType\":\"user\",\"subjectId\":\"bob\",\"access\":\"view\"}]}",
                    "alice").statusCode());

            // olly holds no share: indistinguishable from absence — read, write, delete, versions
            assertEquals(404, send(c.port, "GET", "/components/widget/w1", null, "olly").statusCode());
            assertEquals(404, send(c.port, "PUT", "/components/widget/w1", "{\"vizType\":\"line\"}", "olly").statusCode());
            assertEquals(404, send(c.port, "DELETE", "/components/widget/w1", null, "olly").statusCode());
            assertEquals(404, send(c.port, "GET", "/components/widget/w1/versions", null, "olly").statusCode());
            assertEquals(0, json(send(c.port, "GET", "/components/widget", null, "olly")).size(),
                    "filtered from the list");

            // bob's view share: reads + list yes, edits no (403 — existence is already known to him)
            assertEquals(200, send(c.port, "GET", "/components/widget/w1", null, "bob").statusCode());
            assertEquals(1, json(send(c.port, "GET", "/components/widget", null, "bob")).size());
            assertEquals(403, send(c.port, "PUT", "/components/widget/w1", "{\"vizType\":\"line\"}", "bob").statusCode());
            assertEquals(403, send(c.port, "DELETE", "/components/widget/w1", null, "bob").statusCode());

            // the owner and the access admin retain full access
            assertEquals(200, send(c.port, "GET", "/components/widget/w1", null, "alice").statusCode());
            assertEquals(200, send(c.port, "GET", "/components/widget/w1", null, "root").statusCode());
        }
    }

    @Test
    void roleShareGrantsEditToEveryHolderOfTheRole(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(200, send(c.port, "POST", "/components/dashboard",
                    "{\"id\":\"ops_board\",\"tiles\":[],\"shares\":[{\"subjectType\":\"role\",\"subjectId\":\"operations\",\"access\":\"edit\"}]}",
                    "alice").statusCode());

            // olly holds `operations` → edit; bob (developer) holds no matching share → 404
            assertEquals(200, send(c.port, "GET", "/components/dashboard/ops_board", null, "olly").statusCode());
            assertEquals(200, send(c.port, "PUT", "/components/dashboard/ops_board",
                    "{\"tiles\":[{\"widgetId\":\"w1\",\"span\":1}]}", "olly").statusCode());
            assertEquals(404, send(c.port, "GET", "/components/dashboard/ops_board", null, "bob").statusCode());

            // olly's edit share is not ownership: no envelope changes, no delete
            assertEquals(403, send(c.port, "PUT", "/components/dashboard/ops_board",
                    "{\"tiles\":[],\"shares\":[{\"subjectType\":\"user\",\"subjectId\":\"olly\",\"access\":\"edit\"}]}",
                    "olly").statusCode(), "only the owner may change the envelope");
            assertEquals(403, send(c.port, "DELETE", "/components/dashboard/ops_board", null, "olly").statusCode());

            // olly's plain save carried the envelope forward
            JsonNode content = json(send(c.port, "GET", "/components/dashboard/ops_board", null, "alice")).get("content");
            assertEquals("alice", content.get("owner").asText());
            assertEquals("operations", content.get("shares").get(0).get("subjectId").asText());

            // the owner may delete
            assertEquals(200, send(c.port, "DELETE", "/components/dashboard/ops_board", null, "alice").statusCode());
        }
    }

    @Test
    void biDatasetSurfaceHonoursTheEnvelope(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            assertEquals(200, send(c.port, "POST", "/components/dataset",
                    "{\"id\":\"fraud_ds\",\"label\":\"fraud\",\"shares\":[{\"subjectType\":\"user\",\"subjectId\":\"bob\",\"access\":\"view\"}]}",
                    "alice").statusCode());
            assertEquals(1, json(send(c.port, "GET", "/bi/datasets", null, "bob")).size());
            assertEquals(0, json(send(c.port, "GET", "/bi/datasets", null, "olly")).size(),
                    "shared-away dataset filtered from /bi/datasets");
        }
    }

    // ── Personal edition (fail-open) ────────────────────────────────────────────────

    @Test
    void withoutAnAuthenticatorTheEnvelopeIsInert(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            assertEquals(200, send(c.port, "POST", "/components/widget",
                    "{\"id\":\"w1\",\"shares\":[{\"subjectType\":\"user\",\"subjectId\":\"bob\",\"access\":\"view\"}]}",
                    null).statusCode());
            JsonNode doc = json(send(c.port, "GET", "/components/widget/w1", null, null));
            assertFalse(doc.get("content").has("owner"), "no subject ⇒ no owner stamp");
            assertEquals(200, send(c.port, "PUT", "/components/widget/w1", "{\"vizType\":\"line\"}", null).statusCode());
            assertEquals(200, send(c.port, "DELETE", "/components/widget/w1", null, null).statusCode());
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────

    private HttpResponse<String> send(int port, String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }
}
