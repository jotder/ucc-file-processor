package com.gamma.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.ops.ObjectType;
import com.gamma.ops.OperationalObject;
import com.gamma.service.CollectorService;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The core's ABAC A3 PEPs in isolation (no policy module — a fake {@link AccessDecider} via
 * {@link AccessDeciders#forTest}): the authorize stage 403s an explicit route-level DENY, ALLOW and
 * ABSTAIN fall through to the existing gates, the decider is never consulted without a Subject, and
 * {@link RowScope} hides a row-level-denied object behind the SEC-7d 404/filter contract. The
 * Enterprise engine's own semantics live in {@code inspecto-policy}'s tests.
 */
class ControlApiAccessDeciderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private static final Authenticator FAKE_AUTH = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer "))
            return Optional.of(new Subject(auth.substring(7), Set.of("canOperateRuns", "canConfigureAccess")));
        return Optional.empty();
    };

    /** Records every consultation; DENY when the subject is "mallory" (route level) or the resource's
     *  {@code flag} attribute is "hidden" (row level); ABSTAIN otherwise. */
    private static final class RecordingDecider implements AccessDecider {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Decision decide(com.sun.net.httpserver.HttpExchange ex, Subject subject, String action,
                               String route, String resourceKind, Map<String, Object> resource) {
            calls.add(action + " " + route + " kind=" + resourceKind + " subject=" + subject.id());
            if (resourceKind == null)
                return "mallory".equals(subject.id()) && !"read".equals(action) ? Decision.DENY : Decision.ABSTAIN;
            return "hidden".equals(resource.get("flag")) ? Decision.DENY : Decision.ABSTAIN;
        }
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        AccessDeciders.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port, RecordingDecider decider) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE_AUTH);
        RecordingDecider decider = new RecordingDecider();
        AccessDeciders.forTest(decider);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port(), decider);
    }

    @Test
    void routeLevelDenyIs403AndAbstainFallsThrough(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            // mallory's write DENY → 403 PERMISSION_DENIED before the handler runs
            HttpResponse<String> denied = send(c.port, "POST", "/runs/nope/trigger", "{}", "mallory");
            assertEquals(403, denied.statusCode());
            assertTrue(denied.body().contains("denied by access policy"), denied.body());

            // the same subject's reads ABSTAIN → the route behaves exactly as before
            assertEquals(200, send(c.port, "GET", "/objects", null, "mallory").statusCode());
            // another subject's write ABSTAINs → the handler's own semantics answer (404 unknown pipeline)
            assertEquals(404, send(c.port, "POST", "/runs/nope/trigger", "{}", "alice").statusCode());
        }
    }

    @Test
    void actionsClassifyReadOperateWrite(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            send(c.port, "GET", "/objects", null, "alice");
            send(c.port, "POST", "/runs/nope/trigger", "{}", "alice");        // gated canOperateRuns → operate
            send(c.port, "PUT", "/access/roles", "{\"roles\":[]}", "alice");       // gated canConfigureAccess → write
            List<String> calls = c.decider().calls;
            assertTrue(calls.stream().anyMatch(s -> s.startsWith("read /objects")), calls.toString());
            assertTrue(calls.stream().anyMatch(s -> s.startsWith("operate /runs/nope/trigger")), calls.toString());
            assertTrue(calls.stream().anyMatch(s -> s.startsWith("write /access/roles")), calls.toString());
        }
    }

    @Test
    void deciderIsNeverConsultedWithoutASubject(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            assertEquals(200, send(c.port, "GET", "/health", null, null).statusCode(),
                    "public probe stays open");
            assertEquals(401, send(c.port, "GET", "/objects", null, null).statusCode(),
                    "unauthenticated non-public route is a 401 before authorization");
            assertTrue(c.decider().calls.isEmpty(), "no Subject → no policy consultation: " + c.decider().calls);
        }
    }

    @Test
    void rowScopeHidesADeniedObjectListAndById(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            var objects = c.svc().objects();
            OperationalObject visible = objects.open(ObjectType.INCIDENT, "ok", "d", "LOW", null, null, null,
                    "corr", Map.of());
            OperationalObject hidden = objects.open(ObjectType.INCIDENT, "secret", "d", "LOW", null, null, null,
                    "corr", Map.of("flag", "hidden"));

            String list = send(c.port, "GET", "/objects?type=INCIDENT", null, "alice").body();
            List<String> ids = JSON.readTree(list).findValuesAsText("id");
            assertTrue(ids.contains(visible.id()));
            assertFalse(ids.contains(hidden.id()), "row-level DENY filters the list");

            assertEquals(404, send(c.port, "GET", "/objects/" + hidden.id(), null, "alice").statusCode(),
                    "by-id: indistinguishable from absence (SEC-7d)");
            assertEquals(200, send(c.port, "GET", "/objects/" + visible.id(), null, "alice").statusCode());
            assertTrue(c.decider().calls.stream()
                            .anyMatch(s -> s.contains("kind=incident") && s.contains("read")),
                    "row-level consultations carry the resolved kind: " + c.decider().calls);
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
