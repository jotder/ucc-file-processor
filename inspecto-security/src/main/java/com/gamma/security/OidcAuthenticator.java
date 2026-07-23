package com.gamma.security;

import com.gamma.control.AccessGrants;
import com.gamma.control.Authenticator;
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
 */
public final class OidcAuthenticator implements Authenticator {

    private final ConfigurableJWTProcessor<SecurityContext> processor;
    private final String rolesClaim;

    /** No-arg constructor required by {@code ServiceLoader}; reads {@code -Dauth.oidc.*}. */
    public OidcAuthenticator() {
        this(jwksSourceFromSystemProperties(), requireProperty("auth.oidc.issuer"),
                System.getProperty("auth.oidc.audience"),
                System.getProperty("auth.oidc.rolesClaim", "roles"));
    }

    /** Explicit-config seam (tests use an in-memory {@code ImmutableJWKSet} — no network dependency). */
    OidcAuthenticator(JWKSource<SecurityContext> jwkSource, String issuer, String audience, String rolesClaim) {
        this.rolesClaim = rolesClaim;
        DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
        p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        JWTClaimsSet.Builder exact = new JWTClaimsSet.Builder().issuer(issuer);
        if (audience != null && !audience.isBlank()) exact.audience(audience);
        p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(exact.build(), Set.of("sub", "exp")));
        this.processor = p;
    }

    @Override
    public Optional<Subject> authenticate(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7))
            return Optional.empty();
        try {
            JWTClaimsSet claims = processor.process(header.substring(7).trim(), null);
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
            return Optional.of(new Subject(subjectId, Set.copyOf(capabilities),
                    RoleMapper.dataScopesFor(claims, rolesClaim, defs)));
        } catch (Exception e) {
            return Optional.empty();   // bad signature, wrong issuer/audience, expired, malformed — all 401
        }
    }

    private static JWKSource<SecurityContext> jwksSourceFromSystemProperties() {
        String uri = requireProperty("auth.oidc.jwksUri");
        try {
            return new RemoteJWKSet<>(new URL(uri));
        } catch (MalformedURLException e) {
            throw new IllegalStateException("invalid -Dauth.oidc.jwksUri=" + uri, e);
        }
    }

    private static String requireProperty(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank())
            throw new IllegalStateException("inspecto-security requires -D" + key
                    + " (Standard edition, docs/EDITIONS.md \"Security direction\")");
        return v;
    }
}
