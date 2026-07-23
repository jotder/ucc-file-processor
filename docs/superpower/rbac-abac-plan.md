# RBAC & ABAC plan — data-driven Roles, server-side enforcement, and the Access Policy engine

**Status:** FINALIZED 2026-07-23 (product decisions locked in-session) — not started. · **Owner:**
enterprise-PM track · **Companions:** `../GLOSSARY.md` §1-A (Lens / Role / Capability / Access
Catalog / Access Profile / Grant — binding) ·
`../archived-documents/plans-archive/rbac-groundwork.md` (the capability seam + role taxonomy) ·
`../archived-documents/plans-archive/lens-access-config-design.md` (catalog/profile/matrix, P3 =
this plan's R2) · `../EDITIONS.md`.

---

## 0. Locked decisions (2026-07-23, operator sign-off)

1. **Role definitions are authorable data** — a per-space `roles.toon` settings doc maps role name →
   capabilities + data scopes. `RoleMapper`'s current built-ins become the *seed defaults*; admins
   tailor grants without a rebuild.
2. **ABAC = a full policy engine** — authorable Access Policies with a condition language evaluated
   per request, not just more hardcoded scope dimensions. Constraint: **hand-rolled and
   dependency-free** (the backend is framework-free and builds offline `-o`; no OPA/embedded-rego).
3. **Edition placement: RBAC ships in Standard, the policy engine ships in Enterprise.**
   Personal stays auth-free and fail-open (unchanged behavior, verbatim SEC-7a design).
   This plan introduces the `edition-enterprise` build flavor (a Maven profile + one new module —
   **never a branch**, per `../BRANCHING.md` §0).
4. **Auth stack direction (operator, 2026-07-23): Keycloak / WSO2** — identity via an external
   OIDC provider (Keycloak or WSO2 Identity Server) and an API gateway (WSO2 API Manager) in
   front of `ControlApi`. Integration is **standards-only** (OIDC discovery, JWT + JWKS,
   RFC 7517/7519) — no vendor SDK on the classpath, so either product (or the split: Keycloak
   IdP + WSO2 gateway) slots in via config alone. Groundwork = R0 (§3) + §5-B; final vendor
   split is §8 Q5.

Standing constraints carried forward: the **core stays auth-free** (everything here lives behind
the existing SPI/middleware seams; `rbac-groundwork.md` §5 remains binding for core) · identity
itself stays in the customer IdP (OIDC; no local user store, no login UI) · UI panes keep gating on
**Capabilities only**, never on role identity (GLOSSARY §1-A).

## 1. Goals / non-goals

**Goals:** real server-side authorization for Standard (roles → capabilities → route gates, plus
Access-Profile surface enforcement and resource sharing); attribute-based policies for Enterprise
(caseType generalized to arbitrary subject/resource attributes, incl. per-tenant Space scoping =
SPC-5); every grant decision auditable.

**Non-goals:** local identity/login/user CRUD (IdP-owned) · per-record field-level masking ·
UI policy *authoring* studio in v1 (policies are TOON-authored + validated; a matrix-style editor
can follow) · retrofitting auth into Personal.

## 2. Architecture — PEP/PDP over the existing seams

Everything hangs off seams that already exist; **no route-file rewiring**:

| Piece | Seam (exists today) | This plan adds |
|---|---|---|
| Identity (PIP) | `Authenticator` SPI → `Subject{id, capabilities, dataScopes}` (`inspecto/…/control/Authenticator.java`, `Subject.java`) | `Subject.attributes()` — a flat `Map<String,Object>` of IdP claims + role-derived attrs (additive; empty map in core) |
| Role resolution | `inspecto-security RoleMapper` (hardcoded switch) | `RoleStore` reads `roles.toon`; `RoleMapper` becomes seed-defaults + fallback |
| Route-level PEP | `ControlApi` middleware chain (S6) + `ApiContext.withCapability` (~24 route classes / ~70 sites) | one new **authorize** middleware stage after `authenticate`; `withCapability` untouched |
| Row-level PEP | `ObjectRoutes` caseType filter (SEC-7d — filtered lists, 404 out-of-scope) | the same filter generalized into a reusable `RowScope` helper driven by the decider |
| PDP | — (capability check is the whole decision) | `AccessDecider` SPI in core: Standard impl = capabilities + Access-Profile grants; Enterprise impl = policy engine layered on top |
| Surface model | Access Catalog + Access Profiles (`AccessRoutes`, TOON via `ComponentStore`) — UI-only today | server-side enforcement when `subjectType: role` (R2, exactly the P3 the schema anticipated) |

**Decision combining (fixed, documented):** Personal/no-Authenticator → allow (fail-open,
unchanged). Otherwise: explicit policy **deny overrides** → explicit policy allow → Access-Profile
grant (role subjects) → capability check → default **deny for writes, allow for reads** on
capability-gated routes (matching today's `requireCapability` semantics). Out-of-scope *data* keeps
the SEC-7d contract: 404, never 403 (no existence leak).

## 3. Workstream R — RBAC (Standard, `inspecto-security`)

- **R0 — OIDC/JWT groundwork. ⚠ REALITY CHECK 2026-07-23: most of R0 already existed** (this item
  was drafted from a stale premise): `inspecto-security` has shipped a working `OidcAuthenticator`
  since W6 — Bearer-JWT signature via **Nimbus JOSE+JWT** JWKS (`RemoteJWKSet`), issuer/audience/
  `exp` verification, `-Dauth.oidc.issuer/jwksUri/audience/rolesClaim` config, Keycloak's
  `realm_access.roles` nesting handled — plus a `KeycloakTokenRelay` (Auth-Code+PKCE server-side
  exchange, refresh tokens never reach the browser). The "hand-rolled dependency-free JOSE" bullet
  below is therefore MOOT (Nimbus is already the signed-off, module-confined dependency; note the
  BACKLOG §3 jlink-vs-Nimbus re-verify row). **R0 remainder ✅ SHIPPED 2026-07-23 — gateway trust
  mode.** As built: `OidcAuthenticator` gains an optional second `DefaultJWTProcessor` (same
  signature/issuer/audience/expiry pipeline, same role→capability resolution incl. R1/R2/R3 seams)
  that validates the gateway-signed `X-JWT-Assertion` header. Opt-in via
  `-Dauth.oidc.gateway.issuer` + `.jwksUri` (optional `.audience`, `.header`; flag table + APIM
  values in `docs/api/deployment/README.md`). Precedence: a present-and-valid `Bearer` always
  decides; the assertion is consulted only when no valid Bearer subject resolves (APIM may pass the
  client's opaque gateway token through in `Authorization`). Plain **unsigned** header identity is
  never trusted — `alg:none` and bare strings fail verification (the X-Actor lesson, test-pinned).
  Gateway flags unset ⇒ byte-identical Bearer-only behaviour (test-pinned). **Clock-skew review
  (closed):** both processors use Nimbus `DefaultJWTClaimsVerifier`'s bounded 60 s default on
  `exp`/`nbf` — adequate, documented, no config knob added. **Still deferred to A1 (by design):**
  the `identity:` claim-mapping block in `roles.toon` (`attributeClaims` allowlist →
  `Subject.attributes()`). Original scope for reference:
  - `OidcAuthenticator implements Authenticator` (in `inspecto-security`): validates
    `Authorization: Bearer` JWTs — issuer + audience + `exp`/`nbf` (bounded clock skew), signature
    against the IdP's **JWKS** (`RS256`/`ES256` via `java.security` — hand-rolled JOSE header/
    payload parse, dependency-free like the rest of the backend). JWKS URL from OIDC discovery,
    cached with `kid`-rollover refetch; **fail-closed** when keys are unreachable past cache TTL.
    No live IdP in tests: real-HTTP test class mints tokens with a local RSA key pair.
  - **Claim mapping is config, not code.** `roles.toon` gains an `identity:` block:
    `{issuer, audience, rolesClaim, attributeClaims: […]}`. Keycloak puts roles at
    `realm_access.roles` (or `resource_access.<client>.roles`); WSO2 uses different claim shapes —
    hardcoding either would bake the vendor in. `rolesClaim` is a `DottedPath` into the verified
    claims; `attributeClaims` is A1's allowlist, landing early. Output stays the existing
    `Subject{id=sub, capabilities←RoleStore, dataScopes}` — nothing downstream changes.
  - **Gateway trust mode (WSO2 APIM).** When the gateway terminates end-user auth and forwards a
    gateway-signed backend JWT (e.g. `X-JWT-Assertion`), `OidcAuthenticator` validates that header
    against the *gateway's* JWKS via the same code path (a second configured issuer). Identity
    from a **plain** (unsigned) header is never trusted — that is the X-Actor lesson, and X-Actor
    itself retires in R2 as planned.
  - *DoD:* valid token → capabilities flow end-to-end; expired/bad-audience/unknown-`kid`/plain-
    header → 401; Personal (no `Authenticator` on classpath) byte-identical fail-open.
- **R1 — authorable `roles.toon`. ✅ SHIPPED 2026-07-23.** As built: core `com.gamma.control.Roles`
  (`@PublicApi`) holds the seed table, the doc parser/validator, and the per-request resolution seam —
  `ControlApi` stamps the bound space's config root on the exchange (`Roles.ATTR_CONFIG_ROOT`) before
  invoking the `Authenticator`, and `Roles.effective(ex)` overlays the authored doc on the seed
  **per role name** (authored `[]` revokes; unnamed seed roles keep defaults), mtime-cached so edits
  apply on the next request, no restart. `GET/PUT /access/roles` in `AccessRoutes` (PUT gated
  `canConfigureAccess`, 503→422 gates; GET marks each row `source: authored|seed`). The security
  module's `RoleMapper` lost its hardcoded switch and resolves through the table; authored roles can
  also carry `dataScopes` (SEC-7d, third scoping source beside the `data_scopes` claim + `case:*`
  roles). **Fail-closed:** an existing-but-unreadable `roles.toon` suspends ALL role grants (never a
  silent seed fallback). ⚠ **Seed table corrected, needs product review (with §8 Q3):** the old
  switch predated five route capabilities that no role granted — incl. `canConfigureAccess`, without
  which a fresh deployment could never author this very table (bootstrap deadlock). New seed: builder
  roles + `canAuthorAlertRules`/`canOfferDatasets`/`canRequestShares`; ops + `canRequestShares`;
  admin + `canConfigureAccess`/`canApproveShares`; super = all nine. All editable via the doc.
  Tests: `ControlApiAccessRolesTest` (real-HTTP gates) + `OidcAuthenticatorTest` (authored
  grant/revoke/no-restart, role dataScopes, unreadable-doc fail-closed). Capability-name validation
  uses `Roles.KNOWN_CAPABILITIES` until R4's manifest replaces it as the source of truth.
- **R2 — Access-Profile enforcement (Lens Access P3). ✅ SHIPPED 2026-07-23 (both halves).**
  As built — one deliberate deviation from the sketch below: there is **no separate authorize
  middleware stage**. Enforcement happens at *authentication* time — core `AccessGrants` resolves
  the subject's held (table-backed) roles against the saved `subjectType: role` profiles over the
  catalog (nearest-ancestor grant, root default allow; **union-of-access across roles** — holding
  an extra role never reduces access; deny binds via catalog *action nodes* → their `capability`),
  and `OidcAuthenticator` strips the denied capabilities before the `Subject` is built. So:
  `Subject` stays capabilities-only (guideline 13 — role names never leave the authenticator),
  every existing `requireCapability` gate enforces profile denies with zero route changes, and the
  v1 envelope/`/bootstrap` `permissions` automatically report *effective* grants. Fail-closed:
  unreadable profile → its role contributes no allows; unreadable catalog (all roles profiled) →
  all capabilities denied. Claim-supplied role names are path-jailed before profile lookup.
  Lens profiles stay UI-only, as designed. Tests: `AccessGrantsTest` (inheritance, union,
  fail-closed, restart-free, path jail) + `OidcAuthenticatorTest` end-to-end deny.
  **UI half — as built (2026-07-23):** the one-file swap promised in rbac-groundwork §2 landed in
  `LensService` with zero pane changes. Under `authMode: 'oidc'` each capability signal now requires
  the matching grant in `SessionService.capabilities()` — the *effective* set `/bootstrap` reports
  after the backend strips profile denies — intersected with the lens view (so "View as Business"
  still previews read-only for a granted subject); Personal/`none` stays byte-identical honor
  system. The switcher constrains to `allowedLenses()` — the lenses the grants project onto per the
  rbac-groundwork §3 taxonomy (Business always; Builder ⇐ `canAuthorWorkbench`; Ops ⇐
  `canOperateRuns`) — and `currentLens` became a computed that coerces to the most capable allowed
  lens without overwriting the stored preference (it snaps back when a revoked role is restored).
  **Nav half:** rides the existing per-lens Access-Profile filtering (`AccessStateService.filterNav`)
  now that the subject can only occupy lenses their roles project onto — role-denied subjects land
  in a lens whose profile hides the denied nodes; no separate role→nav channel is possible or needed
  client-side (capabilities-only contract — role names never reach the SPA). Supporting fixes:
  `SessionService.init()` re-reads `/bootstrap` with the fresh bearer after a resume (the first,
  anonymous read carries no session slice), and the offline mock gained a
  `localStorage['inspecto.mockCapabilities']` override to exercise constrained subjects.
- **R3 — sharing RBAC (datasets / widgets / dashboards). ✅ SHIPPED 2026-07-23.** As built:
  registry components (every `ComponentStore` kind, not just the three headliners) accept an
  optional `owner` + `shares: [{subjectType: role|user, subjectId, access: view|edit}]` envelope —
  additive TOON keys inside the content, absent on every existing doc. Decision logic is core
  `ComponentAccess` (package `com.gamma.control`): **no `shares` key ⇒ byte-identical today's
  behavior** (`owner` alone is provenance, auto-stamped from the authenticated subject at create —
  a bare owner must not silently privatize every authenticated create); once `shares` is present
  the component is restricted — owner + `canConfigureAccess` holders (the governance escape hatch;
  an orphaned IdP `sub` must never brick a doc) get full access, matching shares grant view/edit,
  everyone else gets the SEC-7d contract: **404 indistinguishable from absence** on read AND
  mutate, and list responses (`GET /components/{type}`, `GET /bi/datasets`) filter silently.
  View-share writes and non-owner deletes/envelope-changes are 403 (existence already known).
  Plain saves carry `owner`/`shares` forward when the body omits them (an edit-share save never
  strips protection); envelope changes and deletes of a shared doc are owner/admin-only; version
  history + restore + the `/test` previews honour the same gates. **Role matching keeps the
  Subject capabilities-only (guideline 13):** `OidcAuthenticator` stamps the recognised
  (table-backed, lowercased) role names on the exchange as `ComponentAccess.ATTR_HELD_ROLES` —
  a server-internal seam mirroring `Roles.ATTR_CONFIG_ROOT`, never serialized; an Authenticator
  that doesn't stamp it just matches no role shares (fail-closed). §8 Q4 resolved as-built: both
  subject types shipped — `user` matches the opaque IdP `sub` (no user directory needed).
  Malformed envelopes 422 on write; a hand-edited malformed stored share grants nothing.
  Personal (no Authenticator) is fail-open: envelopes are inert, all routes unchanged. Tests:
  `ControlApiComponentSharesTest` (real HTTP: owner stamp, 404-hiding, role/user shares,
  carry-forward, admin bypass, fail-open) + `OidcAuthenticatorTest` held-roles stamp. Known
  residuals (deliberate): direct `ComponentStore` writers (BiTemplates apply, bundle import,
  Exchange snapshot) bypass the envelope — server-internal paths; row-level generalization of
  this filter to other list families is A3's `RowScope`. Retired the BACKLOG §3/§6 "sharing
  RBAC" duplicate pair.
- **R4 — capability→route manifest. ✅ SHIPPED 2026-07-23.** As built: `CapabilityManifest`
  (core, package-private) declares all 70 gated registrations (`method + pattern → capability`,
  grouped by route class); `CapabilityManifestTest` source-scans the route files for
  `withCapability` sites and asserts exact two-way congruence (drift fails the build), plus the
  vocabulary invariants — every route-demanded capability is granted by ≥1 seed role (the pre-R1
  orphan-capability bug class can't recur) and `Roles.KNOWN_CAPABILITIES` is now *derived from* the
  manifest (single source of truth for the roles.toon 422 set). Access-Catalog action nodes now 422
  on a capability outside the manifest vocabulary.
- **R5 — Admin visibility. ✅ SHIPPED 2026-07-23.** As built: Settings ▸ Access is now tabbed —
  **Lenses** (the existing matrix, unchanged) + **Roles** (`access-roles.component`, lazy
  `matTabContent`). The Roles tab renders one card per effective role (source badge
  `Authored | Seed default`, capability chips, data scopes) and edits through `role-form.dialog`
  (name immutable after create — it is the IdP claim key; capability checkboxes; comma-separated
  SEC-7d scopes; dirty-guarded). **Authored-overlay semantics surface directly in the UX** (no
  hidden state): every save PUTs the full authored set — editing a seed role moves it into the
  overlay, one "Revert" action drops the override (seed role → shipped defaults, custom role →
  removed), exactly `PUT /access/roles`'s settings-doc contract. The **effective-grants view**
  (roles.toon ∘ profile matrix) is the strike-through overlay: a capability the role grants but its
  own `subjectType: role` Access Profile denies (resolved over the derived catalog's action nodes,
  the same projection `AccessGrants` enforces at authentication) renders struck with an explanatory
  tooltip + `sr-only` text. Capability vocabulary = catalog-bound capabilities ∪ every capability
  any role row grants (the seed `super` role carries the full route-gate set, so nothing route-only
  like `canApproveShares` can be silently stripped by the checkbox editor). Fail-closed states
  surfaced: the unreadable-doc `error` renders a "Role grants suspended" alert; editing gates on
  `canConfigureAccess` (UI + method-level). Role *assignment* stays in the IdP (claims), stated in
  the pane copy. Offline: the mock `access.handler` now serves `GET/PUT /access/roles` with the
  seed table + persisted authored overlay and the backend's 422 gates. Tests:
  `access-roles.component.spec` (cards, vocabulary union, profile-deny strike, revert/edit overlay
  PUTs, read-only gate, fail-closed alert, axe) + `role-form.dialog.spec`.

## 4. Workstream A — ABAC policy engine (Enterprise, new `inspecto-policy` module)

- **A1 — attribute model. ✅ SHIPPED 2026-07-23.** As built: `Subject` gained an additive
  `attributes()` map (empty on every pre-A1 caller — delegating constructors keep all call sites
  source-identical); `roles.toon` gained the optional `identity: {attributeClaims: […]}` allowlist
  (validated on the same doc grammar, round-tripped through `GET/PUT /access/roles` with
  full-replace semantics — omitting it clears it), and `OidcAuthenticator` copies exactly the
  allowlisted-and-present verified claims onto the Subject (never the raw token; nothing when the
  doc is unreadable — attributes fail closed alongside the role grants; both Bearer and gateway
  assertions flow through the same path). Resource/environment attribute *binding* (envelope →
  `resource.*`, action/route → `env.*`) is the A3 PDP's context assembly, not shipped here.
  Original scope: subject attributes: `id`, `roles`, `capabilities`, `dataScopes`,
  selected IdP claims (allowlisted in `roles.toon`, never the raw token), bound `space`. Resource
  attributes: `kind`, `id`, `space`, `caseType`, `tags`, `classification`, `owner` (from the
  component/object envelope). Environment: `action` (read/write/operate), `route`.
- **A2 — Access Policy documents. ✅ SHIPPED 2026-07-23** (authoring + grammar; evaluation is A3).
  As built: `com.gamma.util.Conditions` (inspecto-util, domain-agnostic — the "one policy engine,
  many policy kinds" library) — recursive-descent, parse-once → `Condition` predicate over nested
  maps via the existing `DottedPath`; strict-Boolean truthiness and type-mismatch-is-false keep
  evaluation deterministic and fail-closed, parse errors carry the offset. Core
  `com.gamma.control.AccessPolicies` (`@PublicApi`) mirrors `Roles`: per-space
  `access-policies.toon`, mtime/size-cached `load`/`effective(ex)` (the A3 engine's read seam),
  one validate grammar shared by the file parser and `GET/PUT /access/policies` in `AccessRoutes`
  (PUT gated `canConfigureAccess` + manifest row; `when` parse-gates as a 422, so an unparseable
  stored doc can only arise from on-disk edits — it marks the whole doc unreadable and the GET
  surfaces "the policy engine denies (fail-closed)"). Conditions are pre-parsed into each `Policy`;
  a blank `when` = constant-true (target-only policy). No UI (plan non-goal) and no evaluation yet —
  on Personal/Standard classpaths a stored policy has no runtime effect until A3's
  `inspecto-policy`. Original scope: the authorable kind is
  **Access Policy** — per-space `access-policies.toon`:
  `{name, effect: allow|deny, target: {actions, resourceKinds}, when: <condition>}`.
  Condition grammar (small, closed): attribute refs (`subject.* / resource.* / env.*` here),
  literals, `== != in contains and or not ( )`. Hand-rolled recursive-descent parser + evaluator,
  zero deps; parse errors are 422s at authoring time (fail-closed: an unparseable stored policy
  denies, loudly).
  **The evaluator is a shared core library, not policy-module internals:** `Conditions` (core/util —
  parse once → predicate over a `Map<String,Object>` context) is domain-agnostic; `inspecto-policy`
  contributes only the access semantics (attribute binding, combining order, the PDP). This is the
  **one policy engine, many policy kinds** decision (2026-07-23): other in-memory rule families —
  Tag Rules, Notification Rules (code-only today), future retention/case conditions — can adopt the
  same grammar + evaluator over their own context maps, each keeping its own canonical kind,
  authoring surface, and trigger semantics (GLOSSARY: one concept → one word; ⛔ never a generic
  "Policy"/"Rule" kind). The *dataset-side* rule families (Expectation / Alert Rule / Decision
  Rule) already share the Query Core filter model evaluated over data — that SQL-substrate family
  stays as-is; no attempt to force one grammar across both substrates.
- **A3 — evaluation + enforcement. ✅ SHIPPED 2026-07-24.** As built: core gained the
  `AccessDecider` SPI (`@PublicApi`; `AccessDeciders` mirrors `Authenticators` — absent = no-op,
  `forTest` seam), an **authorize stage** in `ControlApi.routeDispatch` (after `authenticate`,
  before the handler; skips the public probe surface and subject-less exchanges; DENY → 403
  `PERMISSION_DENIED` "denied by access policy"), and the **`RowScope`** row-level PEP —
  `ObjectRoutes.visibleTo` now consults it beside the SEC-7d data-scope filter (list filtering +
  by-id 404, kind = the lowercased object type, resource attrs = the object's attribute map +
  kind/id/owner). Action vocabulary binds as read (GET/HEAD) / **operate** (a state change whose R4
  manifest gate is `canOperateRuns` — `CapabilityManifest.capabilityFor`) / write (other state
  changes). `inspecto-policy` (artifactId `file-processor-policy`, `edition-enterprise` profile =
  standard + policy) ships `PolicyEngine implements AccessDecider`: deny-overrides → allow →
  ABSTAIN over `AccessPolicies.effective(ex)` (the A2 mtime cache IS the compiled-policy cache —
  policies are pre-parsed, a PUT bumps mtime); context = `subject.id/capabilities/dataScopes/roles`
  (roles via the R3 held-roles exchange attribute) + A1 claims flattened under `subject.*`,
  `env.action/route/space`, and row-level `resource.*` with `resource.space` defaulting to the
  bound space (the A4 seam). Unreadable doc = DENY every decision, loudly (⚠ including the repair
  PUT — fix on disk; same posture as R1's suspended grants). **Deliberate deviations/deferrals:**
  (1) an explicit policy **allow does NOT bypass capability gates** (defense in depth — §2's order
  put policy-allow first; revisit only with a concrete need); (2) `RowScope` adopters beyond
  objects deferred (components/datasets already have R3's `ComponentAccess`; generalize when a
  policy use-case demands it); (3) `PolicyEngine` wraps nothing — the "Standard decider" is the
  existing capability/profile enforcement, which ABSTAIN falls through to; (4) `package.ps1
  -Edition Enterprise` packaging flavor deferred — the file is another session's uncommitted edit
  (BACKLOG §6 row); CI edition builds unchanged (no workflow builds editions today). Original
  scope: `PolicyEngine implements AccessDecider` (ServiceLoader,
  Enterprise classpath only), wrapping the Standard decider per §2's combining order. Route-level:
  the authorize stage. Row-level: the `RowScope` helper generalizes `ObjectRoutes`' SEC-7d filter to
  any policy-scoped list endpoint (objects first; components/datasets when R3 lands). Compiled
  policies cached, invalidated on `PUT`.
- **A4 — Space scoping = SPC-5. ✅ SHIPPED 2026-07-24.** As built: two engine-resident seed
  policies in `PolicyEngine.SEED` (`inspecto-policy`), overlaid **per policy name** by the authored
  doc — the `Roles.SEED` discipline decided over seeding `access-policies.toon` at space creation
  (uniform across pre-existing spaces; the auth-free core never writes Enterprise policy text; no
  inert docs on Personal/Standard; disable/tailor by authoring the same name — an `allow`
  replacement neutralizes a seed since policy-allow never bypasses capability gates).
  `space-isolation` denies route+row access when the subject's home space ≠ the bound space
  (`env.space`); `space-isolation-rows` denies rows carrying an explicit foreign `resource.space`.
  Both denies engage **only when the subject has a mapped `space` home-space claim** (A1
  `attributeClaims` — a deployment that hasn't mapped one gets no isolation rather than a bricked
  API; strict deployments can author a harder policy) and exempt `canConfigureAccess` holders (the
  operator escape hatch, same capability as R3's sharing bypass). **Server-global surface note:**
  `EventLog.currentSpaceId()` never returns null — un-prefixed routes (`/spaces`, `/spaces/_meta`,
  …) bind `env.space` to the **default** space, so a home-space subject other than `default` is
  denied there too (strict isolation of the cross-space management surface; the operator is exempt,
  and a `default`-home subject reaches them). Deferral: `GET /access/policies`
  surfaces only the authored doc — seed visibility rides the §8 Q3 policy-authoring UX. Tests:
  `PolicyEngineTest` seeded-isolation trio (cross-space deny route+row, operator/unmapped/unbound
  exemptions, per-name authored override incl. seeds-stay-in-force). Retires BACKLOG SPC-5 row.
  Original scope: per-tenant isolation ships as *seeded policies*
  (`deny when resource.space != subject.space` + an operator exemption), not bespoke code — the
  proof the engine earns its keep.
- **A5 — decision audit.** Every deny (and policy-matched allow) → an Audit Log entry
  (`access.denied` / `access.granted`: subject, route/resource, matched policy). Uses the existing
  audit seam; no new store.

## 5. Edition & build wiring

`edition-enterprise` Maven profile = `edition-standard` + `inspecto-policy` (which depends on
`inspecto-security`). Registration via `META-INF/services` like `Authenticator`. `package.ps1`
`-Edition` gains `Enterprise`; CI builds all three flavors per PR (BRANCHING §5 already requires
every edition green). One version spans all editions; artifacts differ by classifier only.

## 5-B. Gateway groundwork (WSO2 API Manager in front of `ControlApi`)

Deployment-topology work, mostly config + docs — none of it blocks R0–R5, but decide it with R0:

- **Contract import:** the versioned `/api/v1` surface + OpenAPI export already exist
  (api-contracts-v1) — that spec is what gets imported into APIM as the managed API. Groundwork:
  keep the export current as routes land (it becomes an external contract, not just docs).
- **Streaming passthrough:** `GET /signals/stream` (SSE) and the live-tail endpoints need response
  buffering disabled on their gateway resource; verify SSE survives APIM mediation before relying
  on it in a gatewayed deployment. List these routes explicitly in the deployment doc.
- **Origin & headers:** behind the gateway the backend sees gateway-originated connections —
  audit entries should record the forwarded client (`X-Forwarded-For`) **only when the request
  came from the trusted gateway** (same trust boundary as the signed-JWT header; never from
  arbitrary clients). CORS for the SPA moves to the gateway edge; the backend's own CORS handling
  stays for gateway-less Standard deployments.
- **UI login flow (SPA):** OIDC Authorization Code + **PKCE** against Keycloak/WSO2 — token
  acquisition/refresh in a `AuthService` + attach-interceptor; no client secret in the SPA. This
  absorbs the BACKLOG §3 "UI sign-out affordance" row (sign-out = local token drop + IdP
  end-session redirect). Personal/mock mode keeps today's no-auth path.
- **Legacy-route interplay:** APIM only fronts `/api/v1` — one more reason the pre-v1 inline
  routes (BACKLOG §5 "v1-only triggers") should retire before a gatewayed deployment is supported.

## 6. Vocabulary (GLOSSARY §1-A additions — land with R1/A2)

**Access Policy** (an authorable allow/deny statement over subject/resource/env attributes —
Enterprise; ⛔ never bare *Rule* or *Policy Rule*) · **Attribute** (a named subject/resource/env
fact a policy conditions on) · update **Role** (now data-defined via `roles.toon`, no longer
"design:" pointer) — and the §13 touchpoint table rows for the new kinds.

## 7. Phasing & DoD

R0 → R1 → R4 → R2 → R3 → R5 (Standard, each independently shippable), then A1+A2 → A3 → A4 → A5.
§5-B gateway items ride alongside R0 (decisions) and R2 (UI login), not as a separate phase.
Each step: full reactor `mvn -o clean test` green **+ `-Pedition-standard`** (and
`-Pedition-enterprise` once it exists) · endpoint-skill real-HTTP tests for every new/changed
route · UI steps green on lint:tokens + `test:ci` + axe · Personal-edition regression = the
existing default-profile suite (fail-open must stay byte-identical).

## 8. Open questions (carry, don't block)

1. **Q3 (rbac-groundwork):** `canTriageRequirements` grant set under real roles — confirm with
   product at R1 seed-authoring time.
2. **X-Actor retirement** (BACKLOG §6 / API-v1 overlap) — fold into R2 (the authorize stage makes
   the spoofable header fully redundant).
3. Policy authoring UX beyond TOON+validation (matrix editor? simulator/"why denied?" explain
   endpoint) — decide after A3 ships with real usage.
4. ~~Does R3 sharing need a `user` subjectType before any user directory exists (IdP `sub` as
   opaque id), or roles-only in v1?~~ **RESOLVED as-built 2026-07-23:** both shipped — `user`
   matches the opaque IdP `sub` (`Subject.id()`), no directory needed; a share to an unknown id
   simply never matches.
5. **Final vendor split** — Keycloak as IdP + WSO2 APIM as gateway, or WSO2 Identity Server for
   both? R0's standards-only design keeps every combination viable; decide before the first
   gatewayed deployment (affects ops runbooks + the NFR-7 control evidence, not code).
