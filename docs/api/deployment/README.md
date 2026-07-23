# `docs/api/deployment/` — WSO2 gateway + Keycloak realm blueprints (W6)

> Companion to [`../../superpower/api-contract-design.md`](../../superpower/api-contract-design.md) §8
> (security architecture) and [`../../EDITIONS.md`](../../EDITIONS.md) (Standard edition assembly).
> These are **illustrative starting points**, not a tested live deployment — this sandbox has no
> running WSO2/Keycloak instance to verify against. Adapt hostnames, realm/API names, and throttling
> tiers to the actual deployment before use.

## What lives here

- **[`wso2-api-definition.yaml`](wso2-api-definition.yaml)** — a WSO2 API Manager `apictl`-importable
  API project descriptor for the `/api/v1` surface. It wraps
  [`../openapi-v1.json`](../openapi-v1.json) — the gateway is **transport only** (§8): routing,
  throttling tiers, CORS, and OAuth2 scope enforcement at most. It never re-implements validation,
  business rules, or persistence (those stay backend-only, per the design's "guideline 26/27: gateway
  transport-only" adoption).
- **[`keycloak-realm-blueprint.json`](keycloak-realm-blueprint.json)** — a partial Keycloak realm
  export: the `inspecto-spa` public client (Authorization Code + PKCE, no client secret — a browser
  SPA cannot keep one), a `roles` protocol mapper so an access token's role grants land in the JWT
  claim `inspecto-security`'s `RoleMapper` reads, and realm roles matching the taxonomy in
  [`../../superpower/rbac-groundwork.md`](../../superpower/rbac-groundwork.md) §3.

## How the pieces fit (§8 recap)

```
Browser ── HTTPS/HTTP2 ──> WSO2 API Gateway ── HTTPS/HTTP1.1 ──> Inspecto (OIDC resource server)
                 │                                    │
                 └── OIDC Auth Code + PKCE ──> Keycloak (users, roles, LDAP/AD federation)
```

1. **Keycloak** authenticates the user via Auth Code + PKCE. The SPA hands the resulting one-time
   `code` to the backend's `POST /auth/exchange` (**backend-mediated session**, W6d): the backend
   redeems it server-to-server and keeps the **refresh token in an httpOnly + SameSite=Strict
   cookie** the page's JavaScript can never read — only short-lived access tokens reach the browser
   (`POST /auth/refresh` mints new ones from the cookie). The access token's `roles` claim (or
   Keycloak's default `realm_access.roles` nesting — `RoleMapper` reads either) carries the
   subject's realm roles.
2. **WSO2** fronts the backend, terminates client TLS, enforces OAuth2 (token introspection or JWT
   validation at the edge — a fast pre-check), rate-limits, and forwards the bearer token upstream
   unchanged.
3. **Inspecto** (`inspecto-security`'s `OidcAuthenticator`) validates the same JWT again — signature
   against Keycloak's JWKS, issuer, audience, expiry — "defense in depth, never trust the gateway
   blindly" — then maps claims → Roles → Capabilities (`RoleMapper`) and attaches a `Subject` the
   control plane's `requireCapability` gates and the v1 envelope's `permissions[]` read from.

## Configuring the backend to match

The blueprint's realm/client names map to the `-Dauth.oidc.*` flags `OidcAuthenticator` reads
(`docs/EDITIONS.md`, [`ControlApi`](../../../inspecto/src/main/java/com/gamma/control/ControlApi.java)):

| Flag | Value for this blueprint |
|---|---|
| `-Dauth.oidc.issuer` | `https://<keycloak-host>/realms/inspecto` |
| `-Dauth.oidc.jwksUri` | `https://<keycloak-host>/realms/inspecto/protocol/openid-connect/certs` |
| `-Dauth.oidc.audience` | `inspecto-api` |
| `-Dauth.oidc.rolesClaim` | `roles` (default — falls back to `realm_access.roles` automatically) |
| `-Dauth.oidc.tokenEndpoint` | derived from the issuer (Keycloak layout) — override for other IAMs |
| `-Dauth.oidc.clientId` | `inspecto-spa` (default) |
| `-Dauth.oidc.clientSecret` | *optional* (public PKCE client needs none). Pass a **`SecretResolver` reference** — `${ENV:NAME}` / `${SYS:prop}` — never the raw value |

`serve.sh`/`serve.bat` (bundled by `package.ps1 -Edition Standard`) read these from
`AUTH_OIDC_ISSUER` / `AUTH_OIDC_JWKS_URI` / `AUTH_OIDC_AUDIENCE` / `AUTH_OIDC_CLIENT_ID` /
`AUTH_OIDC_CLIENT_SECRET` environment variables — the secret is forwarded as an `${ENV:…}` reference
the backend resolves at use, so neither the bundle nor the process command line ever holds it.

### Gateway trust mode (WSO2 APIM `X-JWT-Assertion`, RBAC R0 — optional)

When the gateway terminates end-user auth and forwards its own **gateway-signed** backend JWT
(APIM's Backend JWT feature, default header `X-JWT-Assertion`), configure a second trust anchor —
`OidcAuthenticator` verifies that header through the exact same signature/issuer/audience/expiry
pipeline against the *gateway's* JWKS:

| Flag | Value |
|---|---|
| `-Dauth.oidc.gateway.issuer` | the backend-JWT issuer APIM signs with (default `wso2.org/products/am`) — setting this enables the mode |
| `-Dauth.oidc.gateway.jwksUri` | the gateway's JWKS endpoint (APIM: `https://<gw-host>/oauth2/jwks`) — required once the issuer is set |
| `-Dauth.oidc.gateway.audience` | *optional* exact-audience check on the assertion |
| `-Dauth.oidc.gateway.header` | *optional*, default `X-JWT-Assertion` |

A `Bearer` token, when present and valid, always decides first; the assertion is consulted only when
no valid Bearer subject resolves (APIM may pass the client's opaque gateway token through in
`Authorization`). A plain **unsigned** header is never trusted — `alg:none` and bare identity strings
fail verification. With the flags unset the mode is off and the header is ignored entirely. Both
paths tolerate Nimbus's default bounded clock skew (60 s) on `exp`/`nbf`.

## Explicitly out of scope

Case-type/business-function data-scoped grants (rbac-groundwork §4 open Q2), the `canOnboardConnections`
split (open Q1), and Enterprise multi-realm/multi-tenant federation are not modeled here — they need
product decisions the linked doc flags as still open.
