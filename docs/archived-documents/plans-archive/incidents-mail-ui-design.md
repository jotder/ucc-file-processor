# Incidents & Case Manager — mail-like UI redesign

_Status: shipping (2026-07-12). Owner: UI. Scope: inspecto-ui + mock; backend follow-ups listed in §7._

## 1. Goal

Rework `/incidents` and `/cases` from the generic list→detail pages into a **mail-like 3-pane
interface** (Gmail metaphor), per product direction 2026-07-12:

| Mail concept | Incidents equivalent |
|---|---|
| Important | **My Cases** (assigned to me) |
| Starred | **Escalated** |
| Inbox | **Identified** (new incident; created with 3-layer categorization) |
| Draft | **Diagnosing** (Identified → Diagnosing by manual **Accept**, or assignment; categorization enforced if missing) |
| Sent | **Resolved** (state change requires a resolution comment) |
| Trash | **Archived** |
| Labels | **Tags** |

## 2. Incident lifecycle (vocabulary change — GLOSSARY §9 updated)

`IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED` (+ `reopen`: Resolved/Archived → Diagnosing).
Priority ladder: **Critical · Major · Minor · Low**.

The backend workflow engine takes statuses from config (`*_workflow.toon`), so no Java change is
needed for the vocabulary — but the **built-in** INCIDENT workflow still says
`OPEN → ASSIGNED → IN_PROGRESS → RESOLVED → CLOSED`. The UI therefore **normalizes** legacy
statuses onto the new lifecycle for display/foldering (`OPEN→IDENTIFIED`,
`ASSIGNED|IN_PROGRESS→DIAGNOSING`, `CLOSED→ARCHIVED`), so it renders correctly against today's
backend AND the new mock lifecycle. CASE keeps its existing lifecycle
(`OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED`) — only the look-and-feel is shared.

## 3. Data model (no backend Java change; carried in `attributes`)

| Concept | Storage (`OperationalObject.attributes`) |
|---|---|
| 3-layer category | `category` = `"L1 / L2 / L3"` (taxonomy in `incident-taxonomy.ts`) |
| Tags | `tags` = CSV |
| Escalated flag (incidents) | `escalated` = `'true'` (CASE keeps its ESCALATED *status*) |
| Postmortem | `postmortem` = JSON (`Postmortem` interface, §5) |

"Me" (My Cases) = `localStorage['inspecto.operator']` → default `'operator'` (auth-free Personal
edition has no session identity).

## 4. Layout — `object-mail.component` (serves both routes via route `data.type`)

```
┌───────────┬──────────────────────────────────┬──────────────────────┐
│ Left nav  │ Toolbar: Accept · Prioritize ▾ · │ Detail panel (opens  │
│ (resize + │  Escalate · Resolve · Archive ·  │ on row click):       │
│ collapse) │  Reopen  |  Refresh · New        │  header + meta +     │
│  folders  ├──────────────────────────────────┤  actions +           │
│  + counts │ <inspecto-data-table> standard,  │  Incident Postmortem │
│  + Tags   │ multiSelect: ☑ ▲ prio category   │  template form       │
│           │ tags  title—description  date    │                      │
└───────────┴──────────────────────────────────┴──────────────────────┘
```

- **Left nav**: drag-resizable (pointer + ArrowLeft/Right on a `role="separator"` handle,
  width persisted in `localStorage`) and collapsible to an icon rail. Folders per type
  (INCIDENT: My Cases / Escalated / Identified / Diagnosing / Resolved / Archived;
  CASE: My Cases / Escalated / Open / Investigating / Resolved / Closed) with live counts,
  plus a **Tags** section derived from loaded rows.
- **List**: shared `<inspecto-data-table>` extended with a `multiSelect` mode
  (ag-Grid 35 `rowSelection: {mode:'multiRow', enableClickSelection:false}` → checkbox +
  header-checkbox column; new `(selectionChange)` output). Row click opens the detail panel.
- **Toolbar actions** operate on the checkbox selection (bulk `forkJoin`):
  - **Accept** (Identified→Diagnosing): prompts the 3-layer categorize dialog when a selected
    incident has no category; assigns to me when unassigned; then `transition('accept')`.
  - **Prioritize**: dropdown Critical/Major/Minor/Low → `PATCH /objects/{id}`.
  - **Escalate / De-escalate**: toggles `attributes.escalated` (INCIDENT) or the `escalate`
    transition (CASE).
  - **Resolve**: dialog with a **required** resolution comment → comment + `transition('resolve')`.
  - **Archive** (INCIDENT) / **Close** (CASE), **Reopen**.
- **Detail panel** (right, closable): title + ticket id, status/severity/priority badges, category,
  tags, assignee; quick actions; **Incident Postmortem template form** (INCIDENT only, §5); link to
  the full detail route (graph/comments/attachments — unchanged `object-detail.component`).

## 5. Postmortem template (stored as `attributes.postmortem` JSON)

Mirrors the product template "Incident Postmortem: [Title] (Ticket ID)":

```ts
interface Postmortem {
    commander: string;        // Incident Commander
    incidentDate: string;     // YYYY-MM-DD
    downtime: string;         // "X hours Y minutes"
    businessImpact: string;
    timeline: { time: string; text: string }[];   // "12:00 UTC — alert fired"
    fiveWhys: string[];                            // exactly 5 rows
    actions: { done: boolean; text: string; owner: string; due: string }[];
}
```

Reactive form with FormArrays (add/remove rows), saved via `PATCH /objects/{id}`.

## 5b. Tags & Tag Rules (added 2026-07-12, same shift)

Tags are **user-created** (registry, per space) and applied **manually or by rule** (GLOSSARY §9):

- **Registry**: `GET/POST /tags` (`Tag {name, createdAt}`; names may not contain commas — CSV
  storage). Created from the nav's Tags "+" inline input, inside the tag dialog, or implicitly by
  saving a Tag Rule. The nav Tags section shows registry ∪ in-use tags (zero-count visible).
- **Manual tagging**: toolbar **Tag** button → tri-state checkbox dialog over the registry
  (checked = all selected have it, indeterminate = some); only touched rows are applied
  (add/remove per target via `PATCH /objects/{id}` CSV merge).
- **Tag Rules** (Gmail filters): `TagRule {name, tag, filter: {type, q, status, priority,
  severity, category-prefix}}` — `GET/POST /tags/rules`, `DELETE /tags/rules/{name}`,
  `POST /tags/rules/{name}/apply` → `{matched, updated}`. Rules **auto-apply on object creation**
  (mock `POST /objects`) and **bulk-apply** on demand ("Apply now" / "Save & apply now") from the
  rules dialog (nav Tags funnel icon). At least one criterion is required (422 otherwise); incident
  status criteria match on the **normalized** lifecycle; saving a rule registers its tag.

## 6. Touched files

- **Shared**: `status-badge.component.ts` (+IDENTIFIED/DIAGNOSING/MAJOR/MINOR tones),
  `data-table.component.*` (+`multiSelect`/`selectionChange`),
  `api/objects.service.ts` (+`update()` PATCH, `CreateObject.attributes`).
- **Mock**: `ops.handler.ts` (accept/archive/reopen actions; INCIDENT creates as IDENTIFIED;
  attributes pass-through on create; `PATCH /objects/{id}`), `operations.seed.ts`
  (new-lifecycle incident seeds with category/tags/escalated/priorities).
- **Feature** (`modules/admin/objects/`): new `object-mail.component.*`, `mail-model.ts`,
  `incident-taxonomy.ts`, `categorize.dialog.ts`, `resolve.dialog.ts`,
  `postmortem-panel.component.*` (+specs); `object-create.dialog.ts` gains category (required,
  INCIDENT) / priority select / tags; routes' `''` targets swap to the mail component;
  the old `objects.component.*` is deleted (orphaned by the swap). Nav: `Cases` → `Case Manager`.
- **Docs**: `GLOSSARY.md` §9 lifecycle + §13 touchpoint row; this design doc.

## 7. Backend follow-ups — ✅ SHIPPED (backend pass, 2026-07-12)

1. ✅ **`PATCH /objects/{id}`** — `ObjectRoutes.patchObject` → `ObjectService.patch` (priority /
   severity / assignee replace + attributes merge; 400 empty body, 404 unknown, SEC-7d scoped).
   `PATCH` added to the `ApiContext`/`ControlApi` routing seam + CORS.
1b. ✅ **Tags + Tag Rules** — `TagRoutes` (`GET/POST /tags`, `GET/POST /tags/rules`,
   `DELETE /tags/rules/{name}`, `POST /tags/rules/{name}/apply`), domain records
   `com.gamma.ops.tag.{Tag,TagRule}` (filter matcher folds legacy incident statuses), registry on
   `ObjectService` with the **auto-apply hook in `open()`**. Writes ride the `WriteGates` fail-closed
   chain and persist `<name>_tag.toon` / `<name>_tagrule.toon` under the write root
   (`-Dassist.write.root`), rescanned by `ServiceBootstrap` — **runtime-created tags survive restart**
   (unlike queues). Saving a rule implicitly registers its tag.
2. ✅ **Built-in INCIDENT workflow** → `IDENTIFIED →(accept) DIAGNOSING →(resolve) RESOLVED
   →(archive) ARCHIVED` (+ resolve/archive from earlier states, reopen: `RESOLVED|ARCHIVED →
   DIAGNOSING`); only ARCHIVED terminal; **reopen clears `closedAt`** (`withStatus` non-terminal now
   resets it). `assign` no longer moves status (no ASSIGNED state). `/ack` stays alert-only; the UI
   drives `/transition`. UI normalization retained for TOON-overridden deployments.
3. ⏳ First-class `category`/`tags` query params on `GET /objects` — still open, low value (the UI
   loads and folders client-side).
4. `docs/BACKLOG.md` entry no longer needed for 1/1b/2; item 3 can be carried there on a clean tree.

Tests: `ControlApiTagRoutesTest` (every gate: 503/422/409/404 + apply idempotence + auto-apply),
`TagRuleTest` (matcher semantics + TOON round-trip), PATCH block in `ControlApiObjectsTest`;
lifecycle tests updated (`WorkflowTest`, `ObjectServiceTest`, `ObjectServiceQueueTest`,
`ControlApiObjectsTest`, `ControlApiQueueRoutesTest`).
