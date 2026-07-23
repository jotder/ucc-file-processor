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

- **R1 — authorable `roles.toon`.** Per-space settings doc (settings-doc discipline like
  `nav-menus.toon`/`icon-map.toon`): `roles: [{name, capabilities: […], dataScopes: […]}]`.
  `GET/PUT /access/roles` in `AccessRoutes` (gated `canConfigureAccess`; endpoint-skill gate order;
  422 via spec validation — capability names validated against the manifest from R4). `RoleStore`
  with seed = today's `RoleMapper` table; `RoleMapper` consults it, falls back to built-ins when the
  doc is absent. *DoD:* real-HTTP test class per the endpoint skill; a role edit changes an
  authenticated subject's capabilities without restart.
- **R2 — Access-Profile enforcement (Lens Access P3).** Profiles with `subjectType: role` are
  resolved server-side in the new authorize stage: route → catalog node (`capability`/`link`
  binding) → nearest-ancestor grant (allow/deny, root default allow — GLOSSARY "Grant" semantics,
  unchanged). Lens profiles stay UI-only. UI: the lens-switcher constrains to Lenses the subject's
  roles project onto; `/bootstrap` returns effective capabilities so `LensService` re-derives its
  signals from the subject (the one-file swap promised in rbac-groundwork §2 — no pane changes).
  *DoD:* a role-denied catalog node 403s server-side AND disappears from that subject's nav.
- **R3 — sharing RBAC (datasets / widgets / dashboards).** Components gain optional
  `owner` + `shares: [{subjectType: role|user, subjectId, access: view|edit}]` (additive TOON;
  absent = today's behavior). Enforced in the component read/write routes via the decider.
  Retires the BACKLOG §3/§6 "sharing RBAC" duplicate pair.
- **R4 — capability→route manifest.** A declared table (code, not config) of
  `route pattern → capability`, asserted by a test that greps the registered routes vs
  `withCapability` call sites — closes the "scattered gates, no audit" gap; also feeds R1's 422
  validation and the Access Catalog's action nodes.
- **R5 — Admin visibility.** Settings ▸ Access gains a **Roles** tab: the R1 editor + a read-only
  "effective grants" view per role (roles.toon ∘ profile matrix). Role *assignment* stays in the
  IdP (claims), by design.

## 4. Workstream A — ABAC policy engine (Enterprise, new `inspecto-policy` module)

- **A1 — attribute model.** Subject attributes: `id`, `roles`, `capabilities`, `dataScopes`,
  selected IdP claims (allowlisted in `roles.toon`, never the raw token), bound `space`. Resource
  attributes: `kind`, `id`, `space`, `caseType`, `tags`, `classification`, `owner` (from the
  component/object envelope). Environment: `action` (read/write/operate), `route`.
- **A2 — Access Policy documents.** ⛔ Never bare "Rule" (GLOSSARY): the authorable kind is
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
- **A3 — evaluation + enforcement.** `PolicyEngine implements AccessDecider` (ServiceLoader,
  Enterprise classpath only), wrapping the Standard decider per §2's combining order. Route-level:
  the authorize stage. Row-level: the `RowScope` helper generalizes `ObjectRoutes`' SEC-7d filter to
  any policy-scoped list endpoint (objects first; components/datasets when R3 lands). Compiled
  policies cached, invalidated on `PUT`.
- **A4 — Space scoping = SPC-5.** Per-tenant isolation ships as *seeded policies*
  (`deny when resource.space != subject.space` + an operator exemption), not bespoke code — the
  proof the engine earns its keep. Retires BACKLOG SPC-5 row.
- **A5 — decision audit.** Every deny (and policy-matched allow) → an Audit Log entry
  (`access.denied` / `access.granted`: subject, route/resource, matched policy). Uses the existing
  audit seam; no new store.

## 5. Edition & build wiring

`edition-enterprise` Maven profile = `edition-standard` + `inspecto-policy` (which depends on
`inspecto-security`). Registration via `META-INF/services` like `Authenticator`. `package.ps1`
`-Edition` gains `Enterprise`; CI builds all three flavors per PR (BRANCHING §5 already requires
every edition green). One version spans all editions; artifacts differ by classifier only.

## 6. Vocabulary (GLOSSARY §1-A additions — land with R1/A2)

**Access Policy** (an authorable allow/deny statement over subject/resource/env attributes —
Enterprise; ⛔ never bare *Rule* or *Policy Rule*) · **Attribute** (a named subject/resource/env
fact a policy conditions on) · update **Role** (now data-defined via `roles.toon`, no longer
"design:" pointer) — and the §13 touchpoint table rows for the new kinds.

## 7. Phasing & DoD

R1 → R4 → R2 → R3 → R5 (Standard, each independently shippable), then A1+A2 → A3 → A4 → A5.
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
4. Does R3 sharing need a `user` subjectType before any user directory exists (IdP `sub` as
   opaque id), or roles-only in v1?
