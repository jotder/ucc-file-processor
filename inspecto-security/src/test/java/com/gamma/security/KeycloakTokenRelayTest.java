package com.gamma.security;

import com.gamma.control.TokenRelay;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests for {@link KeycloakTokenRelay}: a local {@link HttpServer} stands in for Keycloak's
 * token endpoint, capturing the form the relay sends and returning canned OIDC token responses — the
 * real {@code java.net.http.HttpClient} wire path is exercised with no IAM and no network.
 */
class KeycloakTokenRelayTest {

    private HttpServer iam;
    private final AtomicReference<Map<String, String>> lastForm = new AtomicReference<>();
    private volatile int respondStatus = 200;
    private volatile String respondBody = """
            {"access_token":"at-1","expires_in":300,"refresh_token":"rt-1","refresh_expires_in":1800}
            """;

    @BeforeEach
    void startFakeIam() throws IOException {
        iam = HttpServer.create(new InetSocketAddress(0), 0);
        iam.createContext("/token", ex -> {
            lastForm.set(parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = respondBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(respondStatus, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        iam.start();
    }

    @AfterEach
    void stopFakeIam() {
        iam.stop(0);
    }

    private KeycloakTokenRelay relay(String clientSecret) {
        return new KeycloakTokenRelay(HttpClient.newHttpClient(),
                URI.create("http://localhost:" + iam.getAddress().getPort() + "/token"),
                "inspecto-spa", clientSecret);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                     URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    @Test
    void exchangeSendsThePkceFormAndParsesTheTokens() {
        Optional<TokenRelay.Tokens> t = relay(null).exchangeCode("the-code", "the-verifier", "http://localhost:4200/");
        assertTrue(t.isPresent());
        assertEquals("at-1", t.get().accessToken());
        assertEquals(300, t.get().expiresInSeconds());
        assertEquals("rt-1", t.get().refreshToken());
        assertEquals(1800L, t.get().refreshExpiresInSeconds());

        Map<String, String> form = lastForm.get();
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("the-code", form.get("code"));
        assertEquals("the-verifier", form.get("code_verifier"));
        assertEquals("http://localhost:4200/", form.get("redirect_uri"));
        assertEquals("inspecto-spa", form.get("client_id"));
        assertFalse(form.containsKey("client_secret"), "a public PKCE client sends no secret");
    }

    @Test
    void confidentialClientSendsItsSecret() {
        assertTrue(relay("s3cr3t").exchangeCode("c", "v", "r").isPresent());
        assertEquals("s3cr3t", lastForm.get().get("client_secret"));
    }

    @Test
    void refreshSendsTheRefreshGrant() {
        assertTrue(relay(null).refresh("rt-0").isPresent());
        Map<String, String> form = lastForm.get();
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals("rt-0", form.get("refresh_token"));
    }

    @Test
    void iamRejectionIsEmptyNotAnException() {
        respondStatus = 400;
        respondBody = "{\"error\":\"invalid_grant\",\"error_description\":\"Code not valid\"}";
        assertTrue(relay(null).exchangeCode("bad", "v", "r").isEmpty());
    }

    @Test
    void malformedTokenResponseIsEmpty() {
        respondBody = "{\"access_token\":\"at-only-no-refresh\"}";
        assertTrue(relay(null).refresh("rt-0").isEmpty());
    }

    @Test
    void unreachableIamIsEmpty() {
        KeycloakTokenRelay dead = new KeycloakTokenRelay(HttpClient.newHttpClient(),
                URI.create("http://localhost:1/token"), "inspecto-spa", null);   // port 1: nothing listens
        assertTrue(dead.exchangeCode("c", "v", "r").isEmpty());
    }

    @Test
    void missingRefreshExpiryYieldsNullNotZero() {
        respondBody = "{\"access_token\":\"at\",\"expires_in\":60,\"refresh_token\":\"rt\"}";
        Optional<TokenRelay.Tokens> t = relay(null).refresh("rt-0");
        assertTrue(t.isPresent());
        assertNull(t.get().refreshExpiresInSeconds(), "absent refresh_expires_in must stay null (session cookie)");
    }
}
