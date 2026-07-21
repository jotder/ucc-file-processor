package com.gamma.control;

import com.gamma.api.PublicApi;

import java.util.Optional;

/**
 * Server-to-server OIDC token exchange for the backend-mediated session pattern (W6d): the SPA never
 * sees the refresh token — {@link AuthRoutes} keeps it in an {@code httpOnly} cookie and calls this
 * relay to mint access tokens against the IAM's token endpoint. Like {@link Authenticator}, this is
 * an edition seam: the Standard edition's {@code inspecto-security} module contributes the Keycloak
 * implementation via {@code META-INF/services/com.gamma.control.TokenRelay}; the auth-free core ships
 * none, so the {@code /auth/*} routes answer {@code 503 CAPABILITY_UNAVAILABLE} on Personal.
 */
@PublicApi(since = "4.0.0")
public interface TokenRelay {

    /** One successful exchange/refresh result. {@code refreshExpiresInSeconds} may be {@code null}
     *  when the IAM does not report it (the cookie then falls back to a session lifetime). */
    record Tokens(String accessToken, long expiresInSeconds,
                  String refreshToken, Long refreshExpiresInSeconds) {}

    /** Redeem an Authorization-Code+PKCE {@code code}. Empty ⇒ the IAM rejected it (expired, wrong
     *  verifier/redirect) — the caller gets {@code 401}, never the IAM's error detail. */
    Optional<Tokens> exchangeCode(String code, String codeVerifier, String redirectUri);

    /** Mint a fresh access token from a refresh token. Empty ⇒ rejected (expired/revoked) — 401. */
    Optional<Tokens> refresh(String refreshToken);

    /** Best-effort revocation on logout; default no-op (the cookie is cleared regardless). */
    default void revoke(String refreshToken) {}
}
