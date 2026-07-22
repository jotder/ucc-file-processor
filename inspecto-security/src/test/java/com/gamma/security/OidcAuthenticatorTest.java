package com.gamma.security;

import com.gamma.control.Authenticator;
import com.gamma.control.Subject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline (no network) tests for {@link OidcAuthenticator}: a self-signed RSA key pair stands in for
 * the IAM's JWKS via {@link ImmutableJWKSet}, so signature/issuer/audience/expiry/role-mapping are all
 * exercised without a running Keycloak/WSO2. A real {@link HttpServer} supplies a genuine
 * {@code HttpExchange} (the JDK type has no public constructor) to carry the {@code Authorization}
 * header under test — same "real transport, not a mock" style as the core's route tests.
 */
class OidcAuthenticatorTest {

    private static final String ISSUER = "https://issuer.example/realms/inspecto";
    private static final String AUDIENCE = "inspecto-api";
    private static RSAKey RSA_KEY;

    @BeforeAll
    static void generateSigningKey() throws Exception {
        RSA_KEY = new RSAKeyGenerator(2048).keyID("k1").generate();
    }

    private static OidcAuthenticator authenticator(String issuer, String audience) {
        JWKSource<SecurityContext> source = new ImmutableJWKSet<>(new JWKSet(RSA_KEY.toPublicJWK()));
        return new OidcAuthenticator(source, issuer, audience, "roles");
    }

    private static String token(Instant exp, List<String> roles, RSAKey signingKey,
                                 String issuer, String audience, String subject) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(subject).audience(audience).expirationTime(Date.from(exp));
        if (roles != null) claims.claim("roles", roles);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    /** Round-trips a real HTTP request through a throwaway server so {@code auth} sees a genuine
     *  {@code HttpExchange} carrying {@code authorizationHeader} (or none, if {@code null}). */
    private static Optional<Subject> authenticateWithHeader(Authenticator auth, String authorizationHeader) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Optional<Subject>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            result.set(auth.authenticate(ex));
            ex.sendResponseHeaders(204, -1);
            ex.close();
            done.countDown();
        });
        server.start();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/"));
            if (authorizationHeader != null) b.header("Authorization", authorizationHeader);
            HttpClient.newHttpClient().send(b.GET().build(), HttpResponse.BodyHandlers.discarding());
            assertTrue(done.await(5, TimeUnit.SECONDS));
            return result.get();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validTokenResolvesSubjectWithMappedCapabilities() throws Exception {
        String jwt = token(Instant.now().plusSeconds(60), List.of("pipeline-developer"), RSA_KEY, ISSUER, AUDIENCE, "jdoe");
        Optional<Subject> subject = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt);
        assertTrue(subject.isPresent());
        assertEquals("jdoe", subject.get().id());
        assertEquals(Set.of(RoleMapper.CAN_AUTHOR_WORKBENCH), subject.get().capabilities());
    }

    @Test
    void adminRoleGrantsOnboardConnectionsAndNotWorkbench() throws Exception {
        // rbac-groundwork §3/§4.1 Q1 (product sign-off 2026-07-22): Connection onboarding is its own
        // Admin-owned grant — Admin gets canOnboardConnections and NOT canAuthorWorkbench (Builder-only).
        String jwt = token(Instant.now().plusSeconds(60), List.of("admin"), RSA_KEY, ISSUER, AUDIENCE, "root");
        Subject admin = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt).orElseThrow();
        assertEquals(Set.of(RoleMapper.CAN_ONBOARD_CONNECTIONS), admin.capabilities());
    }

    @Test
    void caseRolesResolveDataScopesAndPlainRolesStayUnscoped() throws Exception {
        // SEC-7d: case:<scope> role names → dataScopes (lower-cased); plain roles → unscoped (null)
        String scoped = token(Instant.now().plusSeconds(60), List.of("operations", "case:Fraud"),
                RSA_KEY, ISSUER, AUDIENCE, "ana");
        Subject ana = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + scoped).orElseThrow();
        assertTrue(ana.scoped());
        assertEquals(Set.of("fraud"), ana.dataScopes());
        assertEquals(Set.of(RoleMapper.CAN_OPERATE_RUNS), ana.capabilities(), "case role grants no capability");

        String plain = token(Instant.now().plusSeconds(60), List.of("operations"), RSA_KEY, ISSUER, AUDIENCE, "ops");
        assertFalse(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + plain)
                .orElseThrow().scoped(), "no scoping claims → unscoped, the pre-scoping behaviour");
    }

    @Test
    void dataScopesClaimResolvesAndUnionsWithCaseRoles() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject("ana").audience(AUDIENCE)
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .claim("roles", List.of("case:fraud"))
                .claim("data_scopes", List.of("billing", "Roaming"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(RSA_KEY));
        Subject ana = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt.serialize()).orElseThrow();
        assertEquals(Set.of("fraud", "billing", "roaming"), ana.dataScopes(),
                "data_scopes claim unions with case:<scope> roles, all lower-cased");
    }

    @Test
    void missingAuthorizationHeaderIsEmpty() throws Exception {
        assertTrue(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), null).isEmpty());
    }

    @Test
    void nonBearerSchemeIsEmpty() throws Exception {
        assertTrue(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Basic dXNlcjpwYXNz").isEmpty());
    }

    @Test
    void expiredTokenIsEmpty() throws Exception {
        String jwt = token(Instant.now().minusSeconds(60), List.of("super"), RSA_KEY, ISSUER, AUDIENCE, "jdoe");
        assertTrue(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt).isEmpty());
    }

    @Test
    void wrongIssuerIsEmpty() throws Exception {
        String jwt = token(Instant.now().plusSeconds(60), List.of("super"), RSA_KEY, "https://not-the-issuer", AUDIENCE, "jdoe");
        assertTrue(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt).isEmpty());
    }

    @Test
    void wrongAudienceIsEmpty() throws Exception {
        String jwt = token(Instant.now().plusSeconds(60), List.of("super"), RSA_KEY, ISSUER, "some-other-api", "jdoe");
        assertTrue(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt).isEmpty());
    }

    @Test
    void signatureFromAnUntrustedKeyIsEmpty() throws Exception {
        // Same declared kid as the trusted key, but different key material — the JWKS lookup finds the
        // real public key by kid, so verification fails on the signature itself, not a missing key.
        RSAKey imposter = new RSAKeyGenerator(2048).keyID("k1").generate();
        String jwt = token(Instant.now().plusSeconds(60), List.of("super"), imposter, ISSUER, AUDIENCE, "jdoe");
        assertTrue(authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt).isEmpty());
    }

    @Test
    void unrecognisedRoleGrantsNoCapabilities() throws Exception {
        String jwt = token(Instant.now().plusSeconds(60), List.of("intern"), RSA_KEY, ISSUER, AUDIENCE, "jdoe");
        Optional<Subject> subject = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt);
        assertTrue(subject.isPresent(), "a valid token with an unknown role is still authenticated");
        assertTrue(subject.get().capabilities().isEmpty(), "but grants nothing — fail-closed");
    }

    @Test
    void superRoleGrantsAllCapabilities() throws Exception {
        String jwt = token(Instant.now().plusSeconds(60), List.of("super"), RSA_KEY, ISSUER, AUDIENCE, "root");
        Optional<Subject> subject = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt);
        assertEquals(Set.of(RoleMapper.CAN_AUTHOR_WORKBENCH, RoleMapper.CAN_OPERATE_RUNS,
                        RoleMapper.CAN_TRIAGE_REQUIREMENTS, RoleMapper.CAN_ONBOARD_CONNECTIONS),
                subject.get().capabilities());
    }
}
