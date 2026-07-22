# Product decisions — RESOLVED (2026-07-22)

_Status: RESOLVED. The open questions in `docs/BACKLOG.md` §7 were put to the product owner on
2026-07-22; the outcomes below are folded into their owning OKF docs and this plan is archived per the
doc-lifecycle rule. The one item that could NOT be decided here (a UX-session judgment) moved to
`docs/BACKLOG.md` §7 as a first-class row, along with the FEATURE_INVENTORY verification chore._

| # | Question | Outcome (2026-07-22) | Folded into |
|---|---|---|---|
| 1 | `canOnboardConnections` split | **Split + implemented.** Own Admin-only grant; write routes `POST/PUT/DELETE /connections` gate on it (not `canAuthorWorkbench`); `RoleMapper admin→canOnboardConnections`, `super→all`; UI `LensService.canOnboardConnections` + access-catalog node. | `okf/backend/editions/auth-security.md` |
| 2 | Case-type data-scoped grants | **Status quo — SEC-7d already shipped** the `caseType` attribute-scope model; no further per-case-type role UI. Space-wide grants otherwise. | `okf/backend/editions/auth-security.md` |
| 3 | Legacy-route sunset timing | **Leave per-deployment** — no global soak number. W8's mechanism stays; each deployment sets its own bar. R8 is a deployment-policy note, not a fixed threshold. | `REQUIREMENTS.md` R8 / API-5 |
| 4 | Structured queries client-compiled (R6) | **Accepted as design, R6 closed.** Server 422 on non-SQL bodies is the deliberate contract; no server-side structured compiler. | `okf/backend/control-plane/queries.md`, `REQUIREMENTS.md` R6 |
| 5 | Parser required-vs-advanced fields (interview #2) | **STILL OPEN — genuine UX blocker.** "Required" is a UX judgment needing someone who has watched real onboarding sessions; no engineering guess. Moved to `BACKLOG.md` §7. | `BACKLOG.md` §7 |
| 6 | Incident/Case mandatory fields + assignment (interview #5) | **Direct assignment** (assignee optional at creation, settable at triage; queues deferred). Mandatory: **title** (already enforced) **+ ≥1 linked entity**. The ≥1-link enforcement flips the current create-then-link contract → tracked in `BACKLOG.md` §7. | `okf/frontend/features/objects.md`, `BACKLOG.md` §7 |
| 7 | Space Template scope (interview #6) | **Config-only by default + opt-in sample-data bundle** ("seed with sample data" at apply-time). | `BACKLOG.md` §3 (menu/template rows) |
| 8 | KPI target ownership (interview #7) | **Business, as a Requirement field.** `kind: 'kpi'` Requirement carries optional `target`/`comparator`/`unit`; persisted by `RequirementRoutes.submit`. | `okf/frontend/features/kpi-reports.md` |
| 9 | FEATURE_INVENTORY gaps | **Not a product question — a verification chore.** Most rows already shipped (record_split, example files — see BACKLOG §7 2026-07-20 note). Residual verification moved to `BACKLOG.md` §7. | `BACKLOG.md` §7 |
