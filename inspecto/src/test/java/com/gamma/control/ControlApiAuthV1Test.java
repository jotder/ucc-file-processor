package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for W6's AuthN/AuthZ gate in {@link ControlApi#dispatch}. The core ships no
 * {@link Authenticator}, so these force one via {@link Authenticators#forTest} to stand in for the
 * Standard edition's {@code inspecto-security} module — the only way to exercise the gate from this
 * module's own test classpath (a real {@code META-INF/services} registration here would poison every
 * other test in {@code inspecto} with an active Authenticator). Always restored in {@link #tearDown}.
 */
class ControlApiAuthV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** {@code Bearer valid} → full grants; {@code Bearer limited} → authenticated but no capabilities;
     *  anything else (absent, garbage) → unauthenticated. */
    private static final Authenticator FAKE = ex -> {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("Bearer valid".equals(auth)) return Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench", "canOperateRuns")));
        if ("Bearer limited".equals(auth)) return Optional.of(new Subject("guest", Set.of()));
        return Optional.empty();
    };

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.GET().build(), BodyHandlers.ofString());
    }

    @Test
    void writeRouteWithoutCredentialsIs401(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/api/v1/components/widget", "{\"id\":\"w1\",\"kind\":\"bar\"}");
            assertEquals(401, r.statusCode());
            assertEquals("UNAUTHENTICATED", JSON.readTree(r.body()).get("error").get("errorCode").asText());
        }
    }

    @Test
    void writeRouteWithoutCapabilityIs403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/api/v1/components/widget", "{\"id\":\"w1\",\"kind\":\"bar\"}",
                    "Authorization", "Bearer limited");
            assertEquals(403, r.statusCode());
            assertEquals("PERMISSION_DENIED", JSON.readTree(r.body()).get("error").get("errorCode").asText());
        }
    }

    @Test
    void writeRouteWithCapabilitySucceedsAndEnvelopeCarriesPermissions(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/api/v1/components/widget", "{\"id\":\"w1\",\"kind\":\"bar\"}",
                    "Authorization", "Bearer valid");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode permissions = JSON.readTree(r.body()).get("permissions");
            assertTrue(permissions.isArray());
            assertTrue(streamText(permissions).contains("canAuthorWorkbench"));
        }
    }

    @Test
    void bootstrapStaysPublicButReflectsAnAuthenticatedCaller(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            // No credentials: bootstrap still 200s, anonymous.
            HttpResponse<String> anon = get(c.port, "/bootstrap");
            assertEquals(200, anon.statusCode());
            JsonNode anonSession = JSON.readTree(anon.body()).get("session");
            assertFalse(anonSession.get("authenticated").asBoolean());
            assertEquals(0, anonSession.get("capabilities").size());

            // With a valid token: bootstrap reports the real session, still without requiring one.
            HttpResponse<String> authed = get(c.port, "/bootstrap", "Authorization", "Bearer valid");
            assertEquals(200, authed.statusCode());
            JsonNode session = JSON.readTree(authed.body()).get("session");
            assertTrue(session.get("authenticated").asBoolean());
            assertEquals("jdoe", session.get("actor").asText());
            assertTrue(streamText(session.get("capabilities")).contains("canAuthorWorkbench"));
        }
    }

    @Test
    void healthStaysOpenWithNoAuthenticatorInvolved(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, get(c.port, "/health").statusCode());
            assertEquals(200, get(c.port, "/ready").statusCode());
        }
    }

    @Test
    void personalEditionUnaffectedWhenNoAuthenticatorIsRegistered(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // No Authenticators.forTest call — Authenticators.active() resolves empty, exactly like Personal.
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/api/v1/components/widget", "{\"id\":\"w1\",\"kind\":\"bar\"}");
            assertEquals(200, r.statusCode(), "no credential required when no Authenticator is present");
            assertNull(JSON.readTree(r.body()).get("permissions"), "no Subject ⇒ no permissions block");
        }
    }

    @Test
    void singleResourceResponseRefinesPermissionsToTheApplicableSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // SEC-7(b): a single-component GET declares {canAuthorWorkbench}; the envelope emits
        // grants ∩ applicable — the session's canOperateRuns is not applicable to a registry component.
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            new com.gamma.pipeline.ComponentStore(root.resolve("registry"))
                    .write("grammar", "g1", java.util.Map.of("delimiter", ","));

            JsonNode one = JSON.readTree(get(c.port, "/api/v1/components/grammar/g1",
                    "Authorization", "Bearer valid").body());
            assertEquals(List.of("canAuthorWorkbench"), streamText(one.get("permissions")),
                    "per-resource ∩ resource-state, not the session-wide set");

            // the list response declares nothing → session-wide array unchanged
            JsonNode list = JSON.readTree(get(c.port, "/api/v1/components/grammar",
                    "Authorization", "Bearer valid").body());
            assertEquals(2, list.get("permissions").size(), "lists keep the session-wide grants");
        }
    }

    @Test
    void xActorHeaderIsRejectedOnStandard(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // SEC-7(a): with an Authenticator active (Standard), a client-supplied X-Actor is a spoof → 403,
        // even alongside valid credentials — the actor must come from the authenticated Subject.
        Authenticators.forTest(FAKE);
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/api/v1/components/grammar",
                    "Authorization", "Bearer valid", "X-Actor", "mallory");
            assertEquals(403, r.statusCode(), r.body());
            assertEquals("PERMISSION_DENIED", JSON.readTree(r.body()).get("error").get("errorCode").asText());
        }
    }

    @Test
    void xActorHeaderStillHonouredOnPersonal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        // No Authenticator (Personal): X-Actor stays the historic actor mechanism — the reject never fires.
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = get(c.port, "/api/v1/components/grammar", "X-Actor", "alice");
            assertEquals(200, r.statusCode(), r.body());
        }
    }

    private static List<String> streamText(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
