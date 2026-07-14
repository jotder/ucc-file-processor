package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-7d data-scoped grants over real HTTP ("a fraud analyst sees fraud cases"): a scoped Subject gets a
 * filtered {@code GET /objects}, a 404 on any direct route to an out-of-scope object (existence-hiding —
 * read AND mutate), and a pruned correlation graph; an unscoped Subject and untyped objects behave exactly
 * as before. Uses the {@link Authenticators#forTest} seam, restored in teardown.
 */
class ControlApiScopedObjectsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer fraud} → scoped to {fraud}; {@code Bearer all} → authenticated, unscoped. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer fraud".equals(auth))
            return Optional.of(new Subject("ana", Set.of("canOperateRuns"), Set.of("fraud")));
        if ("Bearer all".equals(auth))
            return Optional.of(new Subject("root", Set.of("canOperateRuns")));   // dataScopes null = unscoped
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(FAKE);
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    /** Seed one fraud-typed, one billing-typed and one untyped incident; link fraud → billing. */
    private record Seed(OperationalObject fraud, OperationalObject billing, OperationalObject untyped) {}

    private Seed seed(Ctx c) {
        var objects = c.svc.objects();
        OperationalObject fraud = objects.open(ObjectType.INCIDENT, "sim swap", "d", "HIGH", null, null, null,
                "corr", Map.of(ObjectRoutes.ATTR_CASE_TYPE, "fraud"));
        OperationalObject billing = objects.open(ObjectType.INCIDENT, "rating drift", "d", "HIGH", null, null, null,
                "corr", Map.of(ObjectRoutes.ATTR_CASE_TYPE, "billing"));
        OperationalObject untyped = objects.open(ObjectType.INCIDENT, "disk full", "d", "LOW", null, null, null,
                "corr", Map.of());
        objects.link(fraud.id(), billing.id(), "related_to", "sys");
        return new Seed(fraud, billing, untyped);
    }

    @Test
    void scopedSubjectSeesFilteredListAndUntypedObjects(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Seed s = seed(c);
            JsonNode list = JSON.readTree(get(c.port, "/objects?type=INCIDENT", "fraud").body());
            List<String> ids = list.findValuesAsText("id");
            assertTrue(ids.contains(s.fraud().id()), "in-scope case visible");
            assertTrue(ids.contains(s.untyped().id()), "untyped objects visible to everyone");
            assertFalse(ids.contains(s.billing().id()), "out-of-scope case filtered from the list");

            // the unscoped subject sees all three
            assertEquals(3, JSON.readTree(get(c.port, "/objects?type=INCIDENT", "all").body()).size());
        }
    }

    @Test
    void outOfScopeObjectIs404OnReadAndMutate(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Seed s = seed(c);
            String billing = s.billing().id();
            assertEquals(404, get(c.port, "/objects/" + billing, "fraud").statusCode(),
                    "direct read: indistinguishable from absence");
            assertEquals(404, post(c.port, "/objects/" + billing + "/watch", "{\"user\":\"ana\"}", "fraud").statusCode(),
                    "mutation gated too — defense in depth");
            assertEquals(404, post(c.port, "/objects/" + billing + "/assign", "{\"assignee\":\"ana\"}", "fraud").statusCode());

            // the same object answers normally in scope and for the unscoped subject
            assertEquals(200, get(c.port, "/objects/" + s.fraud().id(), "fraud").statusCode());
            assertEquals(200, get(c.port, "/objects/" + billing, "all").statusCode());
            assertEquals(200, post(c.port, "/objects/" + s.fraud().id() + "/watch", "{\"user\":\"ana\"}", "fraud").statusCode());
        }
    }

    @Test
    void correlationGraphPrunesOutOfScopeNeighbours(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Seed s = seed(c);
            JsonNode scoped = JSON.readTree(get(c.port, "/objects/" + s.fraud().id() + "/graph?depth=2", "fraud").body());
            assertEquals(1, scoped.get("nodes").size(), "billing neighbour pruned");
            assertEquals(s.fraud().id(), scoped.get("nodes").get(0).get("id").asText());
            assertEquals(0, scoped.get("edges").size(), "edges touching pruned nodes dropped");

            JsonNode full = JSON.readTree(get(c.port, "/objects/" + s.fraud().id() + "/graph?depth=2", "all").body());
            assertEquals(2, full.get("nodes").size(), "unscoped subject keeps the full graph");
            assertEquals(1, full.get("edges").size());
        }
    }

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(int port, String path, String body, String bearer) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }
}
