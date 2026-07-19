# Product decisions — draft recommendations (awaiting sign-off)

_Status: DRAFT, 2026-07-20. Not a plan-in-execution — a proposal set for a product owner to
approve/reject/amend. Sourced from the open questions in `docs/BACKLOG.md` §7. On approval, fold
each decision into its owning OKF doc and delete the corresponding BACKLOG row; if this file empties
out, archive it per the doc-lifecycle rule instead of leaving it as a stale root topic._

## 1. `canOnboardConnections` split (REQUIREMENTS.md R8, rbac-groundwork.md)

**Question:** should the capability to onboard/configure Connections be its own grant, separate
from the broader `canAuthorWorkbench` capability currently covering it (today folded into Admin)?

**Recommendation: split it out.** Connections are the credential/network-egress surface — the
blast radius of a bad actor here (arbitrary outbound endpoints, stored secrets) is categorically
worse than authoring a Pipeline or Component. A Pipeline Developer who can build pipelines against
*existing* connections but can't mint new ones is a normal least-privilege shape or teams already
expect. Proposed grant: `canOnboardConnections` (Admin only by default), distinct from
`canAuthorWorkbench` (Pipeline Developer + Admin) which keeps editing pipelines/components. Ties
into the `RoleMapper` claims table (`inspecto-security`) — additive, no migration needed since no
Standard customer is live yet.

## 2. Case-type data-scoped grants (rbac-groundwork.md open Q2)

**Question:** should Incident/Case permissions be scoped per case-type (e.g. "can triage Fraud
cases but not HR cases") or space-wide?

**Recommendation: space-wide for V1, case-type scoping deferred.** No case-type taxonomy exists
yet in the Case Store schema (`InvestigationCaseStore` currently has no `caseType` discriminator
field), so scoping now means inventing a taxonomy speculatively. Ship the simpler space-wide grant;
revisit only once a real customer's case types are known — this avoids building an abstraction
before its shape is settled (CLAUDE.md §2).

## 3. Legacy-route sunset timing (REQUIREMENTS.md R8, API-5/W8)

**Question:** when does the `-Dapi.legacy.routes=off` flip actually happen for real deployments?

**Recommendation: this is genuinely deployment-specific, not a code decision** — W8 already shipped
the mechanism (30-day zero-traffic soak via `inspecto_legacy_api_requests_total`, then `off`, then a
release later the routes are physically deleted). No further engineering work is blocked on this;
what's needed from product is only a **policy statement** ("30 days at zero is our bar") to close
R8's mention of it, since the number was picked engineering-side without sign-off.

## 4. Structured (non-SQL) Queries client-compiled (REQUIREMENTS.md R6/DAT-3)

**Question:** should structured (builder-UI) Queries compile to SQL server-side eventually, or stay
client-compiled indefinitely (server 422s anything non-SQL today)?

**Recommendation: stay client-compiled, close R6 rather than carry it as a live risk.** The current
422 is explicit and the UI's `parameters.ts`/query builder already produces valid SQL text that
`QueryExecutor` runs — a server-side structured compiler would duplicate that logic for no
functional gain (nothing today needs a *server* to accept structured querybodies over the wire).
Recommend downgrading R6 from "risk" to "accepted design," unless product has a concrete external
API consumer in mind that must submit structured (non-SQL) query bodies directly.

## 5. Interview backlog #2 — parser required-vs-advanced fields

**Question:** across the 9 supported parser formats, which options are `required` vs `advanced` in
each format's `AttributeSpec`?

**Recommendation: this needs a person who has watched real onboarding sessions, not an engineering
guess** — "required" here means "a new user will be confused/blocked without it," which is a UX
judgment, not a technical one. Proposing to punt with a placeholder heuristic (delimiter/encoding
required, everything else advanced) would bake in an arbitrary answer that's expensive to unwind
once forms ship. No recommendation offered; flag as a genuine blocker needing a product/UX session.

## 6. Interview backlog #5 — Incident/Case mandatory fields + assignment model

**Question:** which fields are mandatory at creation vs triage; assignment via queues or direct?

**Recommendation: direct assignment for V1, queues deferred.** Direct assignment (assignee field,
optional at creation, settable at triage) is the simpler mechanism and matches how RCA/impact
playbooks already attribute a `Case` to whoever ran the investigation (`docs/okf/.../case-store.md`
if present, else `CaseStoreTest`). Queues imply a routing/ownership-transfer model with no current
consumer — defer until multi-analyst workflows are actually observed. Mandatory-at-creation fields:
recommend title + at minimum one linked entity/incident-source (a Case with nothing linked isn't
useful); everything else (assignee, priority, tags) optional at creation, settable at triage.

## 7. Interview backlog #6 — Space Template scope

**Question:** does a Space Template ship config-only, or does it include sample data by default?

**Recommendation: config-only by default, with an explicit opt-in for a sample-data bundle.**
Config-only keeps a template lightweight and avoids surprising a customer with unwanted synthetic
data landing in a case-sensitive investigative product. An opt-in "seed with sample data" checkbox
at template-apply time serves the demo/onboarding use case without changing the default contract.

## 8. Interview backlog #7 — KPI target ownership

**Question:** are KPI targets/thresholds authored by Business (as a Requirement) or Builder (as
implementation)?

**Recommendation: Business, as a Requirement field.** This mirrors the already-answered #4 pattern
(Business submits a Requirement, Builder delivers against it) — a KPI target is a business
acceptance criterion, not an implementation detail, so it belongs on the Requirement object
Builder is asked to satisfy, not buried in the Component/dashboard that implements it.

## 9. FEATURE_INVENTORY gaps (stale since 2026-06-20 — needs re-verification, not a product call)

Re-flagging rather than recommending: LDIF `record_split` proposal, structured/`text_regex` block
records, missing example files, `package.ps1` dir pre-creation — these are **verification** tasks
(check current code against the doc's claims), not open product questions. Recommend a follow-up
session runs `backend-explorer` against each row and updates `FEATURE_INVENTORY.md` directly; no
product input required.
