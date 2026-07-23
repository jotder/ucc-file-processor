package com.gamma.security;

import com.gamma.control.Authenticator;
import com.gamma.control.Roles;
import com.gamma.control.Subject;
import dev.toonformat.jtoon.JToon;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.Map;
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

    private static Optional<Subject> authenticateWithHeader(Authenticator auth, String authorizationHeader) throws Exception {
        return authenticateWithHeader(auth, authorizationHeader, null);
    }

    /** Round-trips a real HTTP request through a throwaway server so {@code auth} sees a genuine
     *  {@code HttpExchange} carrying {@code authorizationHeader} (or none, if {@code null}). A non-null
     *  {@code configRoot} is stamped as {@link Roles#ATTR_CONFIG_ROOT} pre-auth, exactly as
     *  {@code ControlApi.authenticate} does — the RBAC R1 per-space roles.toon seam. */
    private static Optional<Subject> authenticateWithHeader(Authenticator auth, String authorizationHeader,
                                                            java.nio.file.Path configRoot) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        AtomicReference<Optional<Subject>> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        server.createContext("/", ex -> {
            if (configRoot != null) ex.setAttribute(Roles.ATTR_CONFIG_ROOT, configRoot);
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
        // Seed table (Roles.SEED, corrected 2026-07-23): builder roles author the workbench, alert
        // rules, and dataset offers, and may request shares.
        assertEquals(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES,
                Roles.CAN_OFFER_DATASETS, Roles.CAN_REQUEST_SHARES), subject.get().capabilities());
    }

    @Test
    void adminRoleGrantsOnboardConnectionsAndNotWorkbench() throws Exception {
        // rbac-groundwork §3/§4.1 Q1 (product sign-off 2026-07-22): Connection onboarding is its own
        // Admin-owned grant — Admin gets canOnboardConnections and NOT canAuthorWorkbench (Builder-only).
        String jwt = token(Instant.now().plusSeconds(60), List.of("admin"), RSA_KEY, ISSUER, AUDIENCE, "root");
        Subject admin = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt).orElseThrow();
        assertEquals(Set.of(Roles.CAN_ONBOARD_CONNECTIONS, Roles.CAN_CONFIGURE_ACCESS,
                Roles.CAN_APPROVE_SHARES), admin.capabilities());
        assertFalse(admin.capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH),
                "canAuthorWorkbench stays Builder-only");
    }

    @Test
    void caseRolesResolveDataScopesAndPlainRolesStayUnscoped() throws Exception {
        // SEC-7d: case:<scope> role names → dataScopes (lower-cased); plain roles → unscoped (null)
        String scoped = token(Instant.now().plusSeconds(60), List.of("operations", "case:Fraud"),
                RSA_KEY, ISSUER, AUDIENCE, "ana");
        Subject ana = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + scoped).orElseThrow();
        assertTrue(ana.scoped());
        assertEquals(Set.of("fraud"), ana.dataScopes());
        assertEquals(Set.of(Roles.CAN_OPERATE_RUNS, Roles.CAN_REQUEST_SHARES), ana.capabilities(),
                "case role grants no capability");

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
        assertEquals(Set.of(Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_OPERATE_RUNS,
                        Roles.CAN_TRIAGE_REQUIREMENTS, Roles.CAN_ONBOARD_CONNECTIONS,
                        Roles.CAN_CONFIGURE_ACCESS, Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_OFFER_DATASETS,
                        Roles.CAN_REQUEST_SHARES, Roles.CAN_APPROVE_SHARES),
                subject.get().capabilities());
    }

    // ── RBAC R1: authored roles.toon resolved per request (no restart) ──────────────

    private static void writeRolesDoc(java.nio.file.Path configRoot, List<Map<String, Object>> roles) throws Exception {
        java.nio.file.Files.writeString(configRoot.resolve("roles.toon"),
                JToon.encode(Map.of("roles", roles)));
    }

    @Test
    void authoredRoleDocGrantsAndRevokesWithoutRestart(@TempDir java.nio.file.Path configRoot) throws Exception {
        OidcAuthenticator auth = authenticator(ISSUER, AUDIENCE);
        String jwt = token(Instant.now().plusSeconds(60), List.of("auditor", "developer"),
                RSA_KEY, ISSUER, AUDIENCE, "ada");

        // custom role granted + seed role revoked, in one authored doc
        writeRolesDoc(configRoot, List.of(
                Map.of("name", "auditor", "capabilities", List.of(Roles.CAN_OPERATE_RUNS)),
                Map.of("name", "developer", "capabilities", List.of())));
        Subject ada = authenticateWithHeader(auth, "Bearer " + jwt, configRoot).orElseThrow();
        assertEquals(Set.of(Roles.CAN_OPERATE_RUNS), ada.capabilities(),
                "authored 'auditor' grants; authored 'developer' with [] revokes its seed grants");

        // edit the doc — the very next request sees the new table (mtime-cached, restart-free)
        writeRolesDoc(configRoot, List.of(
                Map.of("name", "auditor", "capabilities",
                        List.of(Roles.CAN_OPERATE_RUNS, Roles.CAN_TRIAGE_REQUIREMENTS))));
        Subject after = authenticateWithHeader(auth, "Bearer " + jwt, configRoot).orElseThrow();
        assertTrue(after.capabilities().contains(Roles.CAN_TRIAGE_REQUIREMENTS), "edit applies without restart");
        assertTrue(after.capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH),
                "'developer' no longer authored — falls back to its seed grants");
    }

    @Test
    void authoredRoleDataScopesScopeTheSubject(@TempDir java.nio.file.Path configRoot) throws Exception {
        writeRolesDoc(configRoot, List.of(Map.of("name", "fraud-analyst",
                "capabilities", List.of(Roles.CAN_OPERATE_RUNS), "data_scopes", List.of("Fraud"))));
        String jwt = token(Instant.now().plusSeconds(60), List.of("fraud-analyst"), RSA_KEY, ISSUER, AUDIENCE, "ana");
        Subject ana = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt, configRoot).orElseThrow();
        assertTrue(ana.scoped());
        assertEquals(Set.of("fraud"), ana.dataScopes(), "role-authored dataScopes, lower-cased (SEC-7d)");
    }

    @Test
    void roleAccessProfileShapesTheSubjectCapabilities(@TempDir java.nio.file.Path configRoot) throws Exception {
        // RBAC R2: a subjectType:role Access Profile denying a catalog action node strips the bound
        // capability from the Subject at authentication time — requireCapability then 403s it.
        java.nio.file.Path catalogDir = java.nio.file.Files.createDirectories(
                configRoot.resolve("registry").resolve("access-catalog"));
        java.nio.file.Files.writeString(catalogDir.resolve("catalog.toon"), JToon.encode(Map.of(
                "version", 1, "nodes", List.of(Map.of("id", "wb.author", "label", "Author",
                        "kind", "action", "capability", Roles.CAN_AUTHOR_WORKBENCH)))));
        java.nio.file.Path profileDir = java.nio.file.Files.createDirectories(
                configRoot.resolve("registry").resolve("access-profiles"));
        java.nio.file.Files.writeString(profileDir.resolve("role-developer.toon"), JToon.encode(Map.of(
                "subjectType", "role", "subjectId", "developer", "label", "Developer",
                "grants", Map.of("wb.author", "deny"))));

        String jwt = token(Instant.now().plusSeconds(60), List.of("developer"), RSA_KEY, ISSUER, AUDIENCE, "dev");
        Subject dev = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt, configRoot).orElseThrow();
        assertFalse(dev.capabilities().contains(Roles.CAN_AUTHOR_WORKBENCH),
                "profile-denied capability stripped server-side");
        assertTrue(dev.capabilities().contains(Roles.CAN_AUTHOR_ALERT_RULES),
                "capabilities not bound to a denied node are untouched");
    }

    @Test
    void unreadableRoleDocSuspendsAllGrantsFailClosed(@TempDir java.nio.file.Path configRoot) throws Exception {
        java.nio.file.Files.writeString(configRoot.resolve("roles.toon"), "roles: [ this is not valid");
        String jwt = token(Instant.now().plusSeconds(60), List.of("super"), RSA_KEY, ISSUER, AUDIENCE, "root");
        Subject root = authenticateWithHeader(authenticator(ISSUER, AUDIENCE), "Bearer " + jwt, configRoot).orElseThrow();
        assertTrue(root.capabilities().isEmpty(),
                "an existing-but-unreadable roles.toon suspends ALL role grants — never a silent seed fallback");
    }
}
