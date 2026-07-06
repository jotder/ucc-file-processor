package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.SourceService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP tests for the W6d backend-mediated session routes ({@code /auth/*}): code exchange sets
 * the {@code httpOnly}+{@code SameSite=Strict} refresh cookie and returns only the access token;
 * refresh rotates the cookie; logout clears it; the Origin gate rejects cross-site calls; and — the
 * Personal-edition invariant — with no {@link TokenRelay} on the classpath every route is a
 * {@code 503 CAPABILITY_UNAVAILABLE}. A fake relay stands in for {@code inspecto-security}'s Keycloak
 * implementation via {@link TokenRelays#forTest} (same rationale as {@code ControlApiAuthV1Test}).
 */
class ControlApiAuthSessionV1Test {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    /** code "good-code" → tokens (refresh "rt-1"); refresh "rt-1" → rotated "rt-2"; all else rejected. */
    private static final TokenRelay FAKE = new TokenRelay() {
        @Override public Optional<Tokens> exchangeCode(String code, String verifier, String redirectUri) {
            return "good-code".equals(code)
                    ? Optional.of(new Tokens("at-1", 300, "rt-1", 1800L)) : Optional.empty();
        }
        @Override public Optional<Tokens> refresh(String rt) {
            return "rt-1".equals(rt)
                    ? Optional.of(new Tokens("at-2", 300, "rt-2", 1800L)) : Optional.empty();
        }
    };

    @AfterEach
    void tearDown() {
        TokenRelays.forTest(null);
        System.clearProperty("auth.origin");
    }

    private record Ctx(SourceService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path cfg) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        SourceService svc = new SourceService(List.of(pipe), List.of(), List.of(), 3600L, 1, null);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    private HttpResponse<String> post(int port, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (headers.length > 0) b.headers(headers);
        return client.send(b.method("POST", body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body)).build(),
                BodyHandlers.ofString());
    }

    private static String setCookie(HttpResponse<String> r) {
        return r.headers().firstValue("Set-Cookie").orElse(null);
    }

    @Test
    void personalEditionAnswers503OnEveryAuthRoute(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg)) {   // no TokenRelays.forTest — Personal: no relay found
            for (String path : List.of("/auth/exchange", "/auth/refresh", "/auth/logout")) {
                HttpResponse<String> r = post(c.port, "/api/v1" + path, "{}");
                assertEquals(503, r.statusCode(), path);
                assertEquals("CAPABILITY_UNAVAILABLE",
                        JSON.readTree(r.body()).get("error").get("errorCode").asText(), path);
            }
        }
    }

    @Test
    void exchangeSetsHttpOnlyCookieAndReturnsOnlyTheAccessToken(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> r = post(c.port, "/api/v1/auth/exchange",
                    "{\"code\":\"good-code\",\"codeVerifier\":\"v\",\"redirectUri\":\"http://localhost:4200/\"}");
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertEquals("at-1", data.get("accessToken").asText());
            assertEquals(300, data.get("expiresIn").asLong());
            String cookie = setCookie(r);
            assertNotNull(cookie);
            assertTrue(cookie.startsWith(AuthRoutes.COOKIE + "=rt-1;"), cookie);
            assertTrue(cookie.contains("HttpOnly"), cookie);
            assertTrue(cookie.contains("SameSite=Strict"), cookie);
            assertTrue(cookie.contains("Max-Age=1800"), cookie);
            assertFalse(cookie.contains("Secure"), "plain-HTTP test server must not set a Secure cookie");
            assertFalse(r.body().contains("rt-1"), "the refresh token never appears in a response body");
        }
    }

    @Test
    void badCodeIs401AndMissingFieldsAre400(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> bad = post(c.port, "/api/v1/auth/exchange",
                    "{\"code\":\"stolen\",\"codeVerifier\":\"v\",\"redirectUri\":\"r\"}");
            assertEquals(401, bad.statusCode());
            assertEquals("UNAUTHENTICATED", JSON.readTree(bad.body()).get("error").get("errorCode").asText());
            assertEquals(400, post(c.port, "/api/v1/auth/exchange", "{\"code\":\"x\"}").statusCode());
        }
    }

    @Test
    void refreshRotatesTheCookieAndARejectedSessionClearsIt(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> ok = post(c.port, "/api/v1/auth/refresh", null,
                    "Cookie", AuthRoutes.COOKIE + "=rt-1");
            assertEquals(200, ok.statusCode(), ok.body());
            assertEquals("at-2", JSON.readTree(ok.body()).get("data").get("accessToken").asText());
            assertTrue(setCookie(ok).startsWith(AuthRoutes.COOKIE + "=rt-2;"), "rotated to the new refresh token");

            HttpResponse<String> dead = post(c.port, "/api/v1/auth/refresh", null,
                    "Cookie", AuthRoutes.COOKIE + "=rt-1-revoked");
            assertEquals(401, dead.statusCode());
            assertTrue(setCookie(dead).contains("Max-Age=0"), "a dead session's cookie is cleared");

            assertEquals(401, post(c.port, "/api/v1/auth/refresh", null).statusCode(), "no cookie ⇒ no session");
        }
    }

    @Test
    void logoutClearsTheCookie(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(FAKE);
        try (Ctx c = open(cfg)) {
            HttpResponse<String> r = post(c.port, "/api/v1/auth/logout", null,
                    "Cookie", AuthRoutes.COOKIE + "=rt-1");
            assertEquals(200, r.statusCode());
            assertTrue(JSON.readTree(r.body()).get("data").get("loggedOut").asBoolean());
            assertTrue(setCookie(r).contains("Max-Age=0"));
        }
    }

    @Test
    void crossOriginCallIsRejectedWhenAnOriginIsConfigured(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(FAKE);
        System.setProperty("auth.origin", "https://inspecto.internal");
        try (Ctx c = open(cfg)) {
            HttpResponse<String> r = post(c.port, "/api/v1/auth/exchange",
                    "{\"code\":\"good-code\",\"codeVerifier\":\"v\",\"redirectUri\":\"r\"}",
                    "Origin", "https://evil.example");
            assertEquals(403, r.statusCode());
            assertEquals("PERMISSION_DENIED", JSON.readTree(r.body()).get("error").get("errorCode").asText());

            HttpResponse<String> same = post(c.port, "/api/v1/auth/exchange",
                    "{\"code\":\"good-code\",\"codeVerifier\":\"v\",\"redirectUri\":\"r\"}",
                    "Origin", "https://inspecto.internal");
            assertEquals(200, same.statusCode(), "the configured origin passes");
        }
    }

    @Test
    void authRoutesStayReachableWithoutABearerTokenEvenWhenAuthenticationIsOn(@TempDir Path cfg) throws Exception {
        TokenRelays.forTest(FAKE);
        Authenticators.forTest(ex -> Optional.empty());   // strictest possible authenticator: rejects everyone
        try {
            try (Ctx c = open(cfg)) {
                HttpResponse<String> r = post(c.port, "/api/v1/auth/exchange",
                        "{\"code\":\"good-code\",\"codeVerifier\":\"v\",\"redirectUri\":\"r\"}");
                assertEquals(200, r.statusCode(), "exchange must not require the token it exists to mint");
            }
        } finally {
            Authenticators.forTest(null);
        }
    }
}
