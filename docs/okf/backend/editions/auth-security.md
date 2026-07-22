---
type: Concept
title: Auth & Security
description: Auth-free core; the Authenticator/Subject/TokenRelay SPIs; the shipped inspecto-security module (Standard, OIDC via Keycloak); the separate -Dassist.write.root write-gate.
resource: inspecto-security/
tags: [auth, security, spi, oidc, keycloak, bff, write-gate, edition-standard]
timestamp: 2026-07-07T00:00:00Z
---

# Auth & Security

**All auth was removed from `master`/the common core on 2026-06-16.** Personal is genuinely auth-free: every
[`ControlApi`](../control-plane/control-api.md) route is open, the SPA boots straight to `/dashboard`, and there
is no token paste / guard / interceptor. The removed hand-rolled bearer-token plane (per-route `Scope`,
`-Dcontrol.token`, the Angular token screen) is gone.

**Standard re-adds auth via SPIs + the shipped `inspecto-security` module.** The core defines three SPIs in
`com.gamma.control`: **`Authenticator`** (validates a request, yields a subject), **`Subject`** (a record of
`id` + capabilities), and **`TokenRelay`**. `inspecto-security/` (artifactId `file-processor-security`, 41
tests) implements them: `OidcAuthenticator` (Nimbus JOSE+JWT), `RoleMapper` (roles from IAM claims), and
`KeycloakTokenRelay`. It joins the reactor **only under the `edition-standard` Maven profile** — the default
build never compiles it (verify with `-Pedition-standard`); because it's a
[build flavor](editions-model.md), the core still carries zero auth code.

**BFF session shape.** The browser never holds tokens: `POST /auth/exchange|refresh|logout` run the OIDC
exchange server-side and keep the refresh token in an **httpOnly `inspecto_rt` cookie**
(`SameSite=Strict`, plus an `Origin` check for CSRF). HTTPS is served by the pure-JDK `HttpsServer`. The UI
discovers the mode via `GET /bootstrap` → `features.authMode` and its OIDC flow is a no-op on Personal.

**The Capability seam (RBAC groundwork, 2026-07-03; seam proven by Lens Access config 2026-07-14).**
Authorization questions are always asked as **named capabilities** (`canAuthorWorkbench`, `canOperateRuns`,
`canTriageRequirements`, `canOnboardConnections`, …) — never "which lens is active?". A **Lens** is a
self-selected *view* (UX shaping, honor system); a **Role** is an admin-*assigned* authorization enforced
server-side (GLOSSARY §1-A, binding). On Standard, `RoleMapper` resolves IAM claims → roles → capabilities
per the planned taxonomy (Business / Pipeline Developer / Operations / Power / Admin / Super); the UI
re-derives its capability signals from the subject's grants in one file — no pane changes. Rules for
extending: one new named capability per distinct authorization question; never reuse one because its current
value happens to match. **Data-scoped grants (SEC-7d, shipped 2026-07-08):** an object's `caseType` attribute
vs `Subject.dataScopes` (null = unscoped; resolved from a `data_scopes` claim ∪ `case:<scope>` role names),
enforced in `ObjectRoutes` — filtered lists, 404 on out-of-scope access, pruned correlation graphs;
event/audit streams stay capability-gated by design. **`canOnboardConnections` (rbac-groundwork §3/§4.1 Q1,
product sign-off 2026-07-22, IMPLEMENTED):** Connection onboarding is its own Admin-owned grant — the write
routes (`POST`/`PUT`/`DELETE /connections`) gate on `canOnboardConnections`, **not** `canAuthorWorkbench`,
because Connections are the credential + network-egress surface (worse blast radius than authoring a pipeline;
a Pipeline Developer builds against *existing* connections but can't mint new ones). `RoleMapper` maps
`admin → canOnboardConnections` and `super → {all}`; the UI mirrors it as `LensService.canOnboardConnections`
(the connections pane's create/edit/delete gate). Still-open security-module questions: RBAC grant set for
`canTriageRequirements`; per-object ACLs/ownership are deferred to Enterprise ABAC.

**The write-gate is separate from auth and stays in all editions.** `-Dassist.write.root` is a path-jailed
filesystem gate on mutation routes (config writes, connection writes, authored-Pipeline CRUD): absent →
those routes return **`503`**; present → writes are jailed to that root and validated by
[`ConfigSafetyValidator`](../config/config-safety.md). It is an ops decision about whether this instance may
write — not authentication.
