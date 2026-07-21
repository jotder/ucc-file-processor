package com.gamma.control;

import com.gamma.api.PublicApi;
import com.sun.net.httpserver.HttpExchange;

import java.util.Optional;

/**
 * Validates an inbound request's credentials and resolves the acting {@link Subject} (W6). This is
 * the seam the Standard edition's {@code inspecto-security} module fills (OIDC resource server: JWT
 * signature/issuer/audience/expiry via Nimbus + JWKS, docs/EDITIONS.md "Security direction"),
 * contributed via {@code META-INF/services/com.gamma.control.Authenticator} and discovered by
 * {@link Authenticators}. The auth-free core ships <b>no</b> implementation: an absent module means
 * {@link Authenticators#active()} is empty and {@link ControlApi#dispatch} enforces nothing at all —
 * Personal edition stays byte-for-byte unchanged.
 */
@PublicApi(since = "4.0.0")
public interface Authenticator {

    /** Resolve the caller from {@code ex}'s credentials (typically an {@code Authorization: Bearer}
     *  header). Empty ⇒ missing or invalid credentials; the caller gets {@code 401 UNAUTHENTICATED}. */
    Optional<Subject> authenticate(HttpExchange ex);
}
