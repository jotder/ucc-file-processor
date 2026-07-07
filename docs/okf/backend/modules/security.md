---
type: Concept
title: Security module (inspecto-security)
description: Standard-edition OIDC resource server behind the Authenticator/Subject/TokenRelay SPIs — Nimbus JWKS, RoleMapper, Keycloak token relay; reactor-gated behind the edition-standard profile.
resource: inspecto-security/
tags: [module, security, oidc, editions, standard, spi]
timestamp: 2026-07-07T00:00:00Z
---

# Security module (inspecto-security)

The fifth Maven module (`file-processor-security`), shipped W6 (2026-07-06). It supplies the
**Standard/Enterprise** auth implementation; the common core stays auth-free.

* **SPI seam (in core)** — `com.gamma.control.Authenticator` / `Subject` (id + capabilities) /
  `TokenRelay`, discovered via `ServiceLoader`. **No-op wins**: with no provider on the classpath the
  Personal edition is byte-for-byte unchanged.
* **Contents** — `OidcAuthenticator` (OIDC resource server on Nimbus JOSE+JWT / JWKS),
  `RoleMapper` (token claims → Roles/Capabilities), `KeycloakTokenRelay`.
* **Reactor gating** — the module only builds under the `edition-standard` Maven profile; the default
  `mvn -o clean test` never compiles it (verify with `-Pedition-standard`, 41 tests).
* **Around it (in core, W6/W6d)** — the AuthN gate + per-route capability checks
  (`UNAUTHENTICATED`/`PERMISSION_DENIED`), HTTPS via the pure-JDK `HttpsServer`, and the BFF routes
  `POST /auth/exchange|refresh|logout` (refresh token only in the httpOnly `inspecto_rt` cookie;
  CSRF = SameSite=Strict + Origin check).
* **Known caveat** — the jlink runtime module set is **not yet re-verified against Nimbus**; run the
  Standard bundle with `-NoRuntime` until it is (REQUIREMENTS PKG-4).

Edition context: [editions model](../editions/editions-model.md) ·
[auth & security](../editions/auth-security.md) · consumer flow: [`/api/v1`](../control-plane/api-v1.md).
