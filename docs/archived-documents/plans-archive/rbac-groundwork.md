# RBAC groundwork — Lens vs Role, the Capability seam, and the planned role taxonomy

**Status:** groundwork landed 2026-07-03 (capability seam + vocabulary); RBAC itself is **security-module
scope** (Standard/Enterprise editions), not the auth-free core. · **Owner:** enterprise-PM track ·
**Companions:** `../GLOSSARY.md` §1-A (binding: Lens / Role / Capability) ·
`frontend-review-and-completion-plan.md` §1 (persona→surface map) · `reviews/lens-shell.md` (W4).

## 1. The distinction (binding, GLOSSARY §1-A)

| | **Lens** (built, W4) | **Role** (planned, security module) |
|---|---|---|
| What it is | A self-selected *view* — filters nav/toolbars | An *assigned authorization* — grants capabilities |
| Who controls it | The user, freely, via "View as" | An admin; enforced server-side |
| Cardinality | Exactly one active | A user may hold several |
| Enforcement today | **Honor system** (auth-free core) | Real (authenticated subject → grants) |

The shipped Business-read-only gating is **UX shaping, not security**. The only real server-side gates in
the core are the write-root (503) and control tokens.

## 2. The Capability seam (what landed 2026-07-03)

Panes never ask "which lens is active?" — they ask a **named capability** on `LensService`
(`inspecto/api/lens.service.ts`):

| Capability | Gates (today) | Under RBAC, granted to |
|---|---|---|
| `canAuthorWorkbench` | Connections / Pipelines / Jobs create-edit-delete (incl. the pipeline canvas defense-in-depth guards) | Pipeline Developer, Power, Super |
| `canOperateRuns` | Runs trigger/pause/resume/reprocess + run-detail reprocess | Operations, Pipeline Developer, Power, Super |
| `canTriageRequirements` | Requirements accept/reject/deliver (C1) | Pipeline Developer, Operations, Power, Super |

Today all three derive from `!readOnly()` (Business lens ⇒ deny). **When the security module lands, one
file re-derives these signals from the subject's role grants — no pane changes.** Rules for extending:
one new named capability per distinct authorization question; never reuse an existing one because its
current value happens to match; never gate on `readOnly`/lens identity in a pane.

## 3. Planned role taxonomy (product owner, 2026-07-03)

| Role | Lens(es) it projects onto | Capabilities beyond the view |
|---|---|---|
| Business user | Business | read-only + Requirements/Reconciliation authoring |
| Pipeline / App Developer | Builder | `canAuthorWorkbench`, Studio authoring |
| Operations / Support | Ops | `canOperateRuns`, incident/case triage |
| Power user | any granted subset | union of held roles' grants; the "View as" switcher becomes constrained to granted Lenses |
| Admin | Settings/Admin surface | user admin, external-system onboarding (Connections — see open Q1) |
| Super user | any | all grants (≈ the core's default behavior today) |
| Case-type roles (grouped business functions) | mostly Ops | **data-scoped** grants (see open Q2) |

## 4. Open questions (record only — they bite when the security module lands)

1. **Does Connection onboarding move to Admin?** The taxonomy puts "external system onboarding
   (Connection etc.)" under Admin; today Connections authoring is a Builder/Workbench surface gated by
   `canAuthorWorkbench` (Wave-1 decision). If it moves, Connections gets its own capability
   (e.g. `canOnboardConnections`) granted to Admin rather than Pipeline Developer.
2. ~~**Case-type / business-function roles are data-scoped**~~ **CLOSED 2026-07-08 (SEC-7d)** — product
   signed the **attribute-scope** model in-session: an object's `caseType` attribute vs
   `Subject.dataScopes` (null = unscoped; resolved by `RoleMapper` from a `data_scopes` claim ∪
   `case:<scope>` role names). Enforced server-side in `ObjectRoutes`: filtered lists, 404 on direct
   access to out-of-scope objects (read and mutate), pruned correlation graphs. The event/audit streams
   stay capability-gated by design (ops surfaces, not case data).
3. **Requirements triage under roles** — today `canTriageRequirements` is granted to every non-Business
   lens (there is no builder-vs-ops distinction in grants). Confirm the RBAC grant set when roles are real.
4. **SLA on Requirements** — declined 2026-07-03 (C1); revisit with roles if wanted.

## 5. Explicitly deferred to the security module (do NOT build in the core)

Identity/login, user model, role assignment UI, an Admin pane, server-side enforcement, lens-switcher
constraint by grants, case-type data scoping. The core stays auth-free by decision
(`../PROJECT_NOTES.md`); editions re-add auth via the security module + `Authenticator` SPI.
