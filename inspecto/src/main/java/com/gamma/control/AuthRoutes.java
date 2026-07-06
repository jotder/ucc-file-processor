package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;

import java.io.IOException;
import java.util.Map;

/**
 * Backend-mediated OIDC session routes ({@code /auth/*}, W6d): the SPA does Authorization Code +
 * PKCE in the browser, then hands the one-time {@code code} here — the backend redeems it via the
 * edition's {@link TokenRelay} and keeps the <b>refresh token in an {@code httpOnly} cookie</b> the
 * page's JavaScript can never read; only the short-lived access token is returned in the body. On
 * Personal (no relay on the classpath) every route answers {@code 503 CAPABILITY_UNAVAILABLE} — the
 * auth-free core needs no session.
 *
 * <p><b>CSRF posture</b> (decision 2026-07-06): the cookie is {@code SameSite=Strict} (a browser
 * never attaches it cross-site), plus an {@code Origin} check — when {@code -Dauth.origin} (falling
 * back to {@code -Dcontrol.cors}) is configured and the request carries an {@code Origin} header, a
 * mismatch is {@code 403}. No double-submit token: no cross-site path can reach the cookie at all.
 *
 * <p>These paths are on {@link ControlApi}'s public allowlist — a caller redeeming a code has no
 * Bearer token yet; the cookie itself is the credential for {@code /auth/refresh}.
 *
 * <pre>
 *   POST /auth/exchange  {code, codeVerifier, redirectUri} → 200 {accessToken, expiresIn} + Set-Cookie
 *   POST /auth/refresh   (cookie)                          → 200 {accessToken, expiresIn} + rotated cookie
 *   POST /auth/logout    (cookie)                          → 200 {loggedOut} + cleared cookie; best-effort revoke
 * </pre>
 */
final class AuthRoutes implements RouteModule {

    /** The refresh-token cookie. Value is the IAM's opaque/JWT refresh token, never decoded here. */
    static final String COOKIE = "inspecto_rt";

    @Override
    public void register(ApiContext api) {
        api.post("/auth/exchange", (e, m) -> exchange(e, api.body(e)));
        api.post("/auth/refresh", (e, m) -> refresh(e));
        api.post("/auth/logout", (e, m) -> logout(e));
    }

    private Object exchange(HttpExchange ex, Map<String, Object> body) {
        TokenRelay relay = relay();   // capability check first: on Personal this is 503 regardless of body
        requireSameOrigin(ex);
        String code = ApiContext.str(body, "code");
        String verifier = ApiContext.str(body, "codeVerifier");
        String redirectUri = ApiContext.str(body, "redirectUri");
        if (code == null || verifier == null || redirectUri == null)
            throw new ApiException(400, "body must include 'code', 'codeVerifier' and 'redirectUri'");
        TokenRelay.Tokens t = relay.exchangeCode(code, verifier, redirectUri)
                .orElseThrow(() -> new ApiException(401, ErrorCodes.UNAUTHENTICATED, "code exchange failed"));
        return respondWithSession(ex, t);
    }

    private Object refresh(HttpExchange ex) {
        TokenRelay relay = relay();   // capability check first: on Personal this is 503, never a cookie-derived 401
        requireSameOrigin(ex);
        String rt = cookie(ex);
        if (rt == null) throw new ApiException(401, ErrorCodes.UNAUTHENTICATED, "no session");
        TokenRelay.Tokens t = relay.refresh(rt).orElseThrow(() -> {
            clearCookie(ex);   // a dead refresh token is gone for good — don't leave the stale cookie behind
            return new ApiException(401, ErrorCodes.UNAUTHENTICATED, "session expired");
        });
        return respondWithSession(ex, t);
    }

    private Object logout(HttpExchange ex) {
        TokenRelay relay = relay();   // capability check first: on Personal this is 503, not a no-op 200
        requireSameOrigin(ex);
        String rt = cookie(ex);
        if (rt != null) relay.revoke(rt);
        clearCookie(ex);
        return Map.of("loggedOut", true);
    }

    /** Rotate the cookie to the (possibly new) refresh token and return only the access-token body —
     *  the refresh token itself never appears in a response body. */
    private static Object respondWithSession(HttpExchange ex, TokenRelay.Tokens t) {
        long maxAge = t.refreshExpiresInSeconds() != null ? t.refreshExpiresInSeconds() : -1;
        setCookie(ex, t.refreshToken(), maxAge);
        return Map.of("accessToken", t.accessToken(), "expiresIn", t.expiresInSeconds());
    }

    private static TokenRelay relay() {
        return TokenRelays.active().orElseThrow(() -> new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                "no session broker on this edition (Standard bundles the inspecto-security module)"));
    }

    /** Origin gate: reject a browser-attributed cross-site call. Only enforced when an allowed origin
     *  is configured and the request carries an {@code Origin} header (non-browser callers omit it). */
    private static void requireSameOrigin(HttpExchange ex) {
        String allowed = System.getProperty("auth.origin", System.getProperty("control.cors"));
        if (allowed == null || allowed.isBlank() || "*".equals(allowed.trim())) return;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals(allowed.trim()))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "cross-origin auth call rejected");
    }

    // ── cookie plumbing (the JDK server has no cookie API — header strings, mirrors the CORS style) ──

    /** {@code maxAge < 0} ⇒ a session cookie (no Max-Age attribute). {@code Secure} rides only on TLS
     *  exchanges — a plain-HTTP dev server would otherwise set a cookie the browser refuses to return. */
    private static void setCookie(HttpExchange ex, String value, long maxAge) {
        StringBuilder c = new StringBuilder(COOKIE).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Strict");
        if (maxAge >= 0) c.append("; Max-Age=").append(maxAge);
        if (ex instanceof HttpsExchange) c.append("; Secure");
        ex.getResponseHeaders().add("Set-Cookie", c.toString());
    }

    private static void clearCookie(HttpExchange ex) {
        String c = COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"
                + (ex instanceof HttpsExchange ? "; Secure" : "");
        ex.getResponseHeaders().add("Set-Cookie", c);
    }

    /** The refresh token from the request's {@code Cookie} header(s), or {@code null}. */
    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of()))
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).trim().equals(COOKIE)) {
                    String v = pair.substring(eq + 1).trim();
                    if (!v.isEmpty()) return v;
                }
            }
        return null;
    }
}
