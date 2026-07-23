---
type: Concept
title: Auth & Security
description: Auth-free core; the Authenticator/Subject/TokenRelay/AccessDecider SPIs; the shipped inspecto-security module (Standard, OIDC via Keycloak/WSO2, data-driven roles, Access-Profile + sharing enforcement); the Enterprise inspecto-policy ABAC engine (authored Access Policies, space isolation, decision audit); the separate -Dassist.write.root write-gate.
resource: inspecto-security/, inspecto-policy/
tags: [auth, security, spi, oidc, keycloak, wso2, bff, write-gate, rbac, abac, policy-engine, edition-standard, edition-enterprise]
timestamp: 2026-07-24T00:00:00Z
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
(the connections pane's create/edit/delete gate).

**RBAC shipped end-to-end (workstream R, R0–R5, 2026-07-23).** The groundwork above is now a working
server-side authorization system, all behind the existing SPIs (core stays auth-free):

- **Data-driven roles (R1).** Role→capability/data-scope grants are authorable: core `com.gamma.control.Roles`
  holds the seed table + doc grammar; `ControlApi` stamps the bound space's config root
  (`Roles.ATTR_CONFIG_ROOT`) pre-auth and `Roles.effective(ex)` overlays a per-space `roles.toon` **per role
  name** (authored `[]` revokes; unnamed seed roles keep defaults), mtime-cached so edits apply next request,
  no restart. `RoleMapper` lost its hardcoded switch and resolves through the table. **Fail-closed:** an
  existing-but-unreadable `roles.toon` suspends all role grants. `GET/PUT /access/roles` author it (PUT gated
  `canConfigureAccess`); Settings ▸ Access ▸ **Roles** tab (R5) edits it with source badges + the
  profile-deny strike-through overlay.
- **Access-Profile enforcement (R2).** Enforcement happens at *authentication* time — `AccessGrants` resolves
  the subject's held roles against saved `subjectType: role` Access Profiles over the Access Catalog
  (nearest-ancestor grant, root default allow, **union across roles**, deny binds via catalog action nodes →
  their capability) and `OidcAuthenticator` strips the denied capabilities before building the `Subject`. So
  the `Subject` stays capabilities-only (role names never leave the authenticator), every `requireCapability`
  gate enforces profile denies with zero route changes, and `/bootstrap permissions` report *effective* grants.
- **Component sharing (R3).** Every `ComponentStore` kind accepts an optional `owner` + `shares:
  [{subjectType: role|user, subjectId, access: view|edit}]` envelope (absent on every existing doc). Core
  `ComponentAccess`: no `shares` key ⇒ byte-identical today; once present the component is restricted (owner +
  `canConfigureAccess` holders full, shares grant view/edit, everyone else the SEC-7d 404/filtered contract on
  read *and* mutate). Role matching rides `ComponentAccess.ATTR_HELD_ROLES` (authenticator-stamped, never
  serialized). See [Exchange & sharing](../control-plane/exchange-sharing.md).
- **Capability manifest (R4).** `CapabilityManifest` declares all gated `method+pattern → capability`
  registrations; `CapabilityManifestTest` source-scans the route files and fails the build on drift, asserts
  every route-demanded capability is granted by ≥1 seed role, and is the single source of truth for
  `Roles.KNOWN_CAPABILITIES`.
- **Gateway trust mode (R0 remainder).** `OidcAuthenticator` gains an optional second JWT processor validating
  a gateway-signed `X-JWT-Assertion` header (WSO2 APIM), consulted only when no valid `Bearer` resolves;
  unsigned header identity is never trusted. Opt-in via `-Dauth.oidc.gateway.issuer/.jwksUri`.

## Enterprise ABAC — the Access Policy engine (`inspecto-policy`, workstream A)

The `edition-enterprise` Maven profile = `edition-standard` + the new **`inspecto-policy`** module
(artifactId `file-processor-policy`), which registers a `PolicyEngine` on the core's **`AccessDecider`** SPI
via `META-INF/services`. Personal/Standard never bundle it and behave byte-identically. Build/test with
`mvn -o -Pedition-enterprise clean test` ([build & test](../build-run/build-test.md)).

- **Attribute model (A1).** `Subject` gained an additive `attributes()` map (empty on every pre-A1 caller).
  `roles.toon` carries an optional `identity: {attributeClaims: […]}` **allowlist**; `OidcAuthenticator`
  copies exactly the allowlisted-and-present verified claims onto the Subject (never the raw token; nothing
  when the doc is unreadable — attributes fail closed alongside role grants). Both Bearer and gateway paths.
- **Condition grammar (A2).** `com.gamma.util.Conditions` (inspecto-util, domain-agnostic — the "one policy
  engine, many policy kinds" library): recursive-descent, parse-once → predicate over a nested `Map` context
  via `DottedPath`; grammar `== != in contains and or not ( )` + literals + dotted refs; strict-Boolean
  truthiness, type-mismatch-is-false, offset-bearing parse errors. Core `AccessPolicies` mirrors `Roles`:
  per-space `access-policies.toon` (`{name, effect: allow|deny, target:{actions?,resourceKinds?}, when?}`),
  mtime-cached, one validate grammar shared by the file parser and `GET/PUT /access/policies` (`when`
  parse-gates 422). Unreadable doc ⇒ the engine DENIES loudly, never "no policies".
- **Enforcement (A3).** `AccessDecider` SPI (core) is consulted at two PEPs: the route-level **authorize
  stage** in `ControlApi.routeDispatch` (after authenticate; DENY → 403; skips public paths + subject-less
  exchanges) and the row-level **`RowScope`** filter (generalizes `ObjectRoutes`' SEC-7d filter — DENY hides
  the row 404/filtered). `PolicyEngine` = deny-overrides → allow → ABSTAIN over `AccessPolicies.effective`;
  context = `subject.{id,capabilities,dataScopes,roles}` + A1 claims, `env.{action,route,space}`,
  `resource.*` (row level, `resource.space` defaulting to the bound space). **A policy allow does NOT bypass
  capability gates** (defense in depth — the plan's §2 order was deliberately tightened); ABSTAIN falls
  through to the Standard capability/profile/sharing gates.
- **Space isolation (A4 = SPC-5).** Per-tenant isolation ships as two **engine-resident seed policies**
  (`PolicyEngine.SEED`: `space-isolation`, `space-isolation-rows`) overlaid **per policy name** by the
  authored doc — deny when the subject's mapped `space` home-space claim ≠ the bound space (route + row).
  They engage **only when a `space` claim is mapped** (unmapped ⇒ no isolation, never a bricked API) and
  exempt `canConfigureAccess` holders. Note: `EventLog.currentSpaceId()` never returns null (falls back to
  the default space), so `env.space` is always bound — un-prefixed server-global routes bind the default
  space and only a default-home or operator subject reaches them.
- **Decision audit (A5).** Every policy DENY (403 route / hidden row) and every route-level policy-matched
  ALLOW is recorded: `PolicyEngine` stamps the matched policy name on the exchange
  (`AccessDecider.ATTR_MATCHED_POLICY`; `<policies-unreadable>` marker on a fail-closed deny) and core
  `AuditTrail.policyDecision(...)` emits `access.denied` / `access.granted` (category `authorization`, with
  actor, ABAC action verb, route, row kind/id, matched policy) via the existing event seam — read back via
  `GET /events?type=ACCESS_DENIED|AUDIT` ([events & metrics](../control-plane/events-metrics.md)). A
  row-level *allow* is deliberately not audited (fires per surviving row — would flood list reads); ABSTAIN
  is not a policy decision and is never audited.

Still-open (carried to [BACKLOG](../../../BACKLOG.md), non-blocking): RBAC grant set for
`canTriageRequirements`; X-Actor header retirement; a policy-authoring UX beyond TOON+validation (matrix
editor / "why denied?" explain, incl. seed-policy visibility); final IdP/gateway vendor split
(Keycloak + WSO2 APIM vs. WSO2 IS); `package.ps1 -Edition Enterprise` packaging flavor.

**The write-gate is separate from auth and stays in all editions.** `-Dassist.write.root` is a path-jailed
filesystem gate on mutation routes (config writes, connection writes, authored-Pipeline CRUD): absent →
those routes return **`503`**; present → writes are jailed to that root and validated by
[`ConfigSafetyValidator`](../config/config-safety.md). It is an ops decision about whether this instance may
write — not authentication.
