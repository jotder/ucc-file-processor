---
type: Feature
title: Incidents & Cases (Objects)
description: Operational objects in a mail-like 3-pane UI — one ObjectMailComponent serving /incidents and /cases via route data, with workflow-driven lifecycle, tags, and case management.
resource: inspecto-ui/src/app/modules/admin/objects/object-mail.component.ts
tags: [feature, objects, incidents, cases, operations, mail-ui, workflow]
timestamp: 2026-07-16T00:00:00Z
---

# Incidents & Cases (Objects)

Routes `/incidents` and `/cases` (Operations nav group) — a single `ObjectMailComponent` parameterized by
route data (`incidents.routes.ts` / `cases.routes.ts`), the canonical
[pane-reuse pattern](../conventions/routing-and-navigation.md). The chain is **Alert → Incident → Case**
([`GLOSSARY.md`](../../../GLOSSARY.md) §9) — never "Issue". Backed by `ObjectsService`; offline via the
`mockOps` [interceptor](../conventions/mock-backends.md).

* **Mail shell** — Gmail-metaphor 3 panes: folder nav (My Cases / Escalated / Identified / Diagnosing /
  Resolved / Archived + Tags) · list · detail panel; both side panes resize via the shared
  `InspectoSplitDirective`. High volume loads honestly via the data-table's
  [Load more strip](../design-system/data-table.md).
* **Lifecycle** — `IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` (+ reopen → Diagnosing); priority
  ladder Critical · Major · Minor · Low. The UI reads **`GET /workflows/{type}`** (BFS-ordered states)
  instead of hardcoding transitions, so TOON-overridden workflows drive the same panes. Resolve requires
  a resolution comment; a soft resolution-readiness warn checks timeline/cause-analysis/corrective
  actions (backend workflow guard is a documented follow-up).
* **Create contract (product sign-off 2026-07-22)** — assignment is **direct**: an `assignee`, optional at
  creation, settable at triage; queue-based routing is deferred (no multi-analyst consumer yet). Mandatory
  at creation: **title** (already enforced, 400) **+ at least one linked entity/incident-source** (a
  case/incident with nothing linked isn't useful). The ≥1-link requirement is the agreed *target* contract:
  today creation is title-only (`POST /objects`, linking is the separate `POST /objects/{id}/links` step —
  create-then-link), so enforcing link-at-create is a tracked change across the route + `object-create.dialog`
  + mock + tests ([`BACKLOG.md`](../../../BACKLOG.md) §7), not yet enforced.
* **Triage is optimistic** — every bulk verb (accept / resolve / archive / reopen / escalate /
  prioritize / tag / case actions) patches the loaded rows + open detail to the expected post-state,
  then reconciles each row with the authoritative server object; failures reload
  ([forms & state](../conventions/forms-and-state.md)). Merge/split/create stay request→refetch.
* **Tags & Tag Rules** — `/tags` + `/tags/rules`, auto-applied when an object opens; TOON-persisted,
  survive restart.
* **Case management** — case **Contents** (member incidents) with **Split & Merge**; variable **Cause
  Analysis** (`postmortem.causeAnalysis[]` + `causeMethod`); **Findings** (disposition/impact/records,
  soft no-disposition prompt); team `assignees` + `targetDate`. **Rule-raised cases**: `CaseRule`
  (`/cases/rules`, evaluate-on-demand, opens-or-attaches idempotently); **case analytics** via
  `GET /objects/analytics?type=` (stat tiles + by-category bar; Studio-dataset binding is a follow-up).

As-built designs (archived):
[`incidents-mail-ui-design.md`](../../../archived-documents/plans-archive/incidents-mail-ui-design.md) ·
[`case-management-design.md`](../../../archived-documents/plans-archive/case-management-design.md).
