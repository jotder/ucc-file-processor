package com.gamma.security;

import com.gamma.control.AccessGrants;
import com.gamma.control.Authenticator;
import com.gamma.control.ComponentAccess;
import com.gamma.control.Roles;
import com.gamma.control.Subject;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.sun.net.httpserver.HttpExchange;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;
import java.util.Set;

/**
 * OIDC resource-server {@link Authenticator} (W6, Standard edition): validates a Bearer JWT's
 * signature against the IAM's published JWKS, plus issuer/audience/expiry — "defense in depth, never
 * trust the gateway blindly" (docs/superpower/api-contract-design.md §8). Discovered via
 * {@code META-INF/services/com.gamma.control.Authenticator}; {@code ControlApi} eagerly resolves the
 * active {@code Authenticator} at startup, so a misconfigured deployment (missing
 * {@code -Dauth.oidc.issuer}/{@code -Dauth.oidc.jwksUri}) fails to boot rather than silently accepting
 * every request. Any parse/verify failure — bad signature, wrong issuer/audience, expired, malformed —
 * yields an empty result (the caller gets 401), never an exception with detail the caller could learn
 * from.
 *
 * <p><b>Gateway trust mode (RBAC R0).</b> When an API gateway (WSO2 APIM) terminates end-user auth
 * and forwards a <em>gateway-signed</em> backend JWT in a header (APIM default:
 * {@code X-JWT-Assertion}), configuring {@code -Dauth.oidc.gateway.issuer} +
 * {@code -Dauth.oidc.gateway.jwksUri} (optional {@code .audience}, {@code .header}) enables a second
 * {@link DefaultJWTProcessor} that validates that header through the exact same signature/issuer/
 * audience/expiry pipeline. A {@code Bearer} token, when present, always decides first; the assertion
 * is consulted only when no valid Bearer subject resolves (APIM may pass the client's opaque gateway
 * token through in {@code Authorization}). Identity from a plain <em>unsigned</em> header is never
 * trusted — that is the X-Actor lesson. Both paths tolerate Nimbus's default bounded clock skew
 * (60&nbsp;s, {@code DefaultJWTClaimsVerifier}) on {@code exp}/{@code nbf}.
 */
public final class OidcAuthenticator implements Authenticator {

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final ConfigurableJWTProcessor<SecurityContext> gatewayProcessor;   // null = gateway mode off
    private final String gatewayHeader;
    private final String rolesClaim;

    /** No-arg constructor required by {@code ServiceLoader}; reads {@code -Dauth.oidc.*}. */
    public OidcAuthenticator() {
        this(jwksSource("auth.oidc.jwksUri"), requireProperty("auth.oidc.issuer"),
                System.getProperty("auth.oidc.audience"),
                System.getProperty("auth.oidc.rolesClaim", "roles"),
                gatewayJwksSourceFromSystemProperties(),
                System.getProperty("auth.oidc.gateway.issuer"),
                System.getProperty("auth.oidc.gateway.audience"),
                System.getProperty("auth.oidc.gateway.header", "X-JWT-Assertion"));
    }

    /** Explicit-config seam (tests use an in-memory {@code ImmutableJWKSet} — no network dependency). */
    OidcAuthenticator(JWKSource<SecurityContext> jwkSource, String issuer, String audience, String rolesClaim) {
        this(jwkSource, issuer, audience, rolesClaim, null, null, null, "X-JWT-Assertion");
    }

    /** Full seam including the optional gateway trust mode (RBAC R0). */
    OidcAuthenticator(JWKSource<SecurityContext> jwkSource, String issuer, String audience, String rolesClaim,
                      JWKSource<SecurityContext> gatewayJwkSource, String gatewayIssuer, String gatewayAudience,
                      String gatewayHeader) {
        this.rolesClaim = rolesClaim;
        this.processor = processor(jwkSource, issuer, audience);
        this.gatewayProcessor = gatewayJwkSource == null ? null
                : processor(gatewayJwkSource, requireValue(gatewayIssuer, "auth.oidc.gateway.issuer"), gatewayAudience);
        this.gatewayHeader = gatewayHeader;
    }

    private static ConfigurableJWTProcessor<SecurityContext> processor(
            JWKSource<SecurityContext> jwkSource, String issuer, String audience) {
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        JWTClaimsSet.Builder exact = new JWTClaimsSet.Builder().issuer(issuer);
        if (audience != null && !audience.isBlank()) exact.audience(audience);
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(exact.build(), Set.of("sub", "exp")));
        return p;
    }

    @Override
    public Optional<Subject> authenticate(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.length() >= 8 && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Optional<Subject> direct = verify(processor, header.substring(7), ex);
            if (direct.isPresent() || gatewayProcessor == null) return direct;
        }
        if (gatewayProcessor == null) return Optional.empty();
        String assertion = ex.getRequestHeaders().getFirst(gatewayHeader);
        if (assertion == null || assertion.isBlank()) return Optional.empty();
        return verify(gatewayProcessor, assertion, ex);
    }

    private Optional<Subject> verify(ConfigurableJWTProcessor<SecurityContext> proc, String token, HttpExchange ex) {
        try {
            JWTClaimsSet claims = proc.process(token.trim(), null);
            String subjectId = claims.getSubject();
            if (subjectId == null || subjectId.isBlank()) return Optional.empty();
            // RBAC R1: the role → grant table is per-request — the bound space's authored roles.toon
            // overlaid on the seed (ControlApi stamps the config root on the exchange pre-auth).
            var defs = Roles.effective(ex);
            var capabilities = new java.util.LinkedHashSet<>(RoleMapper.capabilitiesFor(claims, rolesClaim, defs));
            // RBAC R2: role-subject Access Profiles enforce server-side by shaping the Subject's
            // capabilities here — only recognised (table-backed) roles count toward the union.
            var held = RoleMapper.roles(claims, rolesClaim).stream()
                    .map(r -> r.toLowerCase(java.util.Locale.ROOT)).filter(defs::containsKey).toList();
            capabilities.removeAll(AccessGrants.deniedCapabilities(ex, held));
            // RBAC R3: expose the recognised role names server-internally so component sharing can
            // match `subjectType: role` shares — the Subject itself stays capabilities-only.
            ex.setAttribute(ComponentAccess.ATTR_HELD_ROLES, Set.copyOf(held));
            return Optional.of(new Subject(subjectId, Set.copyOf(capabilities),
                    RoleMapper.dataScopesFor(claims, rolesClaim, defs)));
        } catch (Exception e) {
            return Optional.empty();   // bad signature, wrong issuer/audience, expired, malformed — all 401
        }
    }

    private static JWKSource<SecurityContext> jwksSource(String propertyKey) {
        String uri = requireProperty(propertyKey);
        try {
            return new RemoteJWKSet<>(new URL(uri));
        } catch (MalformedURLException e) {
            throw new IllegalStateException("invalid -D" + propertyKey + "=" + uri, e);
        }
    }

    /** Gateway mode is opt-in: no {@code -Dauth.oidc.gateway.issuer} → no second processor. */
    private static JWKSource<SecurityContext> gatewayJwksSourceFromSystemProperties() {
        String issuer = System.getProperty("auth.oidc.gateway.issuer");
        if (issuer == null || issuer.isBlank()) return null;
        return jwksSource("auth.oidc.gateway.jwksUri");
    }

    private static String requireValue(String value, String key) {
        if (value == null || value.isBlank())
            throw new IllegalStateException("inspecto-security requires -D" + key
                    + " (Standard edition, docs/EDITIONS.md \"Security direction\")");
        return value;
    }

    private static String requireProperty(String key) {
        return requireValue(System.getProperty(key), key);
    }
}
