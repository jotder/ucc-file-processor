# Editions — Personal / Standard / Enterprise

> Editions are **build/packaging flavors over one codebase** — the same `ServiceLoader`-module + `-D`-flag
> mechanism already used to omit the hosted-AI SDKs from air-gapped builds. **Editions are NEVER git
> branches** (see [`BRANCHING.md`](BRANCHING.md)); there is no `personal`/`standard` branch. An edition is
> *which modules are assembled* from a given commit.

## Matrix

| Aspect | **Personal** | **Standard** | **Enterprise (future)** |
|---|---|---|---|
| Transport | Plain HTTP, **bind localhost only** | HTTPS (`HttpsServer` + keystore; FIPS provider for Gov) | HTTPS, TLS at LB/gateway |
| AuthN | **None** | **Delegated to an external IAM** (Keycloak / WSO2 / Okta / Entra) — app is an OIDC/OAuth2 **resource server** | Same, centralized IAM + token introspection |
| AuthZ | None | **RBAC + ABAC** from IAM token claims/groups | RBAC/ABAC + per-tenant boundaries |
| User mgmt / LDAP / SAML | n/a | **IAM's job** (federates AD/LDAP, brokers SAML) | IAM's job |
| Secrets | env / file | `SecretsProvider` (file + OS keystore, or Vault) | Vault / cloud secrets manager |
| At-rest encryption | optional (volume) | Volume encryption + AES-GCM for stored creds | Volume + shared-store encryption |
| Audit | local append-only logs | **actor-attributed**, tamper-evident compliance log | shared, centralized audit store |
| State | local disk | local disk (or optional Postgres) | **shared backends** (Postgres / object store / Vault) |
| Scheduler | in-JVM | in-JVM | **distributed coordination** (leader election / locks) |
| Compliance scope | none | SOC 2 / ISO 27001 / FedRAMP / HIPAA / PCI | inherits Standard + multi-node controls |
| Packaging | core fat-JAR, `-Dauth.mode=none` | core + `security` module, `-Dauth.mode=oidc`, TLS on | + shared-store modules, coordinator |

## Assembly model (how an edition is produced)

| Mechanism | Used for |
|---|---|
| **Separate Maven module** (e.g. `inspecto-security`) | Standard-only code (OIDC resource-server, TLS, RBAC). Personal simply doesn't bundle it. |
| **Maven profiles** (`-Pedition-personal` / `-Pedition-standard`) | Which modules + shade includes go into the fat-JAR. |
| **`ServiceLoader`** | Runtime discovery — absent module ⇒ the no-op impl is the only one found (mirrors the optional assist agent). |
| **`-D` flags** (`-Dauth.mode`, TLS on/off) | Runtime toggles within an edition. |
| **`package.ps1 -Edition …`** | Emits the per-edition bundles from one build. |

The core engine never contains `if (edition == …)` branches; it depends on SPIs (`Authenticator`,
`SecretsProvider`, …) and the **build** decides which implementation ships. One SemVer version spans all
editions; artifacts differ by classifier (`-personal` / `-standard`).

## Status — core is auth-free (2026-06-16)

The hand-rolled bearer-token control plane that earlier versions baked into `ControlApi`
(per-route `Scope` + `Tokens` + `requireAuth`, `-Dcontrol.token` / `-Dassist.*.token`, the
Angular token-paste `/connect` screen + `authInterceptor` + route guard) has been **removed
from the common core / `master`**. The Personal edition is now genuinely auth-free: every
ControlApi route is open and the SPA boots straight to the dashboard with no login.

Authentication is no longer a core concern — it becomes an **edition** concern. The
Standard/Enterprise editions re-introduce it out-of-band (see below) behind an `Authenticator`
SPI seam, so the engine keeps no auth code and fixes/features land once in common. This realigns
the code with the model already described here: editions add modules; they are never branches.

**Status (2026-07-06, W6):** the `Authenticator` SPI (`com.gamma.control.Authenticator`/`Subject`)
and the AuthN/AuthZ gate in `ControlApi.dispatch` are shipped in the core (edition-neutral — a no-op
when no implementation is on the classpath). The `inspecto-security` module ships the Standard
implementation (`OidcAuthenticator`, Nimbus JOSE+JWT + JWKS) and is reactor-gated behind the
`edition-standard` Maven profile, so it is never built or resolved by a routine Personal build.
`package.ps1 -Edition Standard` builds and bundles it; see `docs/superpower/api-contract-design.md`
§10 W6 for the full slice and `docs/api/deployment/` for WSO2/Keycloak blueprints.

## Security direction (Standard)

Authentication is **delegated to an external IAM** — the app validates IAM-issued JWTs (Nimbus + JWKS:
issuer/audience/expiry) and enforces authorization from claims. The IAM owns user management, AD/LDAP
federation, and SAML brokering, so none of that lives in the Java core. The Angular UI uses OIDC
Authorization Code + PKCE (public client, no shipped secret).

This is **incremental hardening on the existing framework-free core** — *not* a Spring Boot / Quarkus
migration. At 5–15 users with a capabilities-based RFP, a framework buys nothing the IAM + small libraries
don't, and a lean dependency tree is a FedRAMP asset (small SBOM, fewer CVEs to attest). The full
assessment + 7-phase hardening roadmap is maintained alongside this repo's planning notes.

## Enterprise (future, distributed) — keep the seams open

Already fits: stateless stage-1 engine, **stateless JWT auth** (no server session ⇒ horizontal scale),
pluggable `DbStatusStore` (Postgres) / `ObjectStore` db backend / `ParquetEventStore`, `SecretsProvider`.
Will need later (don't preclude now): distributed scheduler coordination, all state on shared backends
(Postgres + object store for Parquet + shared secrets), work distribution, per-tenant ABAC.
