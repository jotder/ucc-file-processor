# Case Management — business differentiation + Split & Merge

_Status: C1 + C2 **SHIPPED** (2026-07-12); C3–C6/I1–I2 proposed. Companion to
`incidents-mail-ui-design.md` (the mail shell both panes share). GLOSSARY §9 chain:
**Alert → Incident → Case**._

> **As-built note (C1/C2):** membership stays on `CONTAINS` links. To support re-pointing, the
> `LinkStore` append-only contract was **deliberately amended** with `remove()` (links = *current*
> correlation state; the immutable audit trail is the Event Log, which records every link change).
> Rejected alternative: membership as an `attributes.caseId` field — it would have created a second,
> competing representation next to the links the glossary/UI/graph already use. Backend:
> `ObjectService.mergeCases/splitCase/unlink` + `POST /objects/{id}/merge|split`,
> `DELETE /objects/{id}/links`; UI: `case-contents.component` (Contents + roll-up + soft close-gate)
> + merge/split/add-member dialogs; mock mirrors all of it (store key v17 seeds memberships).

## 1. As-built today: how Cases and Incidents actually differ

One UI (`object-mail.component`, route-data-driven), one backend model (`OperationalObject`, one
store), one feature set. The only real differences:

| Axis | Incident | Case |
|---|---|---|
| Lifecycle | Identified → Diagnosing → Resolved → Archived (accept/resolve/archive/reopen) | Open → Investigating → Escalated → Resolved → Closed (investigate/escalate/resolve/close) |
| Escalation | orthogonal flag (`attributes.escalated`, the mail "star") | a lifecycle **state** |
| Postmortem panel | ✅ template form | ❌ |
| SLA sweep | ✅ (`sweepIncidentSla` queries INCIDENT only) | ❌ |
| Raised by | Alerts / Diagnoses / operators (high volume, symptom-level) | operators (low volume) |

Shared: tags + Tag Rules, priority ladder, queues/assignment/watchers, comments/attachments,
links/graph, RCA templates, the 3-pane mail shell. The glossary's defining idea — *"a Case is a
group of related Incidents managed as one larger investigation"* — exists only as `CONTAINS`
links (`ObjectLink`), visible in the detail graph but not operable anywhere.

**Conclusion:** Cases are currently incidents with renamed states. The differentiator to build is
the **container/investigation** nature.

## 2. Product direction: Incident = operational, Case = business

| | Incident | Case |
|---|---|---|
| Answers | "something is broken — fix it" | "what happened, what did it cost, what do we decide" |
| Grain | one symptom/disruption | one investigation spanning many incidents |
| Cadence | minutes–days, SLA-driven | days–months, outcome-driven |
| Primary audience | ops lens (SRE/operator) | business lens (analyst / fraud / revenue-assurance) |
| Closure means | service restored (postmortem) | disposition reached (finding + impact + actions) |

### 2b. Resolution patterns (product refinement, 2026-07-12)

**An Incident's resolution is a mandatory pattern** — it may not reach Resolved without the four
sections addressed, plus the summary block (id, title, **Incident Commander**, dates, impact, …):

1. **Timeline of Events** — ordered list, add/alter freely.
2. **Cause Analysis** — ordered list; *usually* The 5 Whys (default form) but not restricted to
   exactly five (generalize today's fixed `fiveWhys[5]` to a variable-length list with a method
   label; keep 5-Whys as the seeded default).
3. **Corrective Actions & Preventative Tasks** — checklist with owner/due.
4. **Defined SLA** — a hard `dueAt` is part of the pattern (the sweep + escalation machinery
   already exists); an incident without an SLA is incomplete.

→ **I1 (incident resolution gate):** Resolve is blocked (soft-warn first, hard-gate once adopted)
until timeline ≥1, cause analysis ≥1, actions ≥1 and `dueAt` set. Enforced in the mail pane's
resolve flow first (the postmortem form is *the* resolution artifact); a backend workflow guard is
the follow-up so the API can't bypass it.

**A Case has the same shape, but generic + configurable + loose:**

| Axis | Incident (strict) | Case (loose/configurable) |
|---|---|---|
| Resolution artifact | fixed 4-section postmortem (above) | **Findings** — configurable sections (RcaTemplate-style TOON: a deployment defines its own section list; disposition + impact built in — C3) |
| Workflow | fixed built-in mail lifecycle | **config-driven** — `*_workflow.toon` override already works backend-side; the UI must stop hardcoding `CASE_FOLDERS` and read the effective workflow (needs a `GET /workflows/{type}` read endpoint — model gap #1) |
| Assignment | one **Incident Commander** (`assignee`) | **team** — multiple persons: keep `assignee` = lead (queues/routing unchanged) + `attributes.assignees` CSV for the working team, watcher-style (model gap #2) |
| Attachments | evidence references (name/uri) | **document-centric** — richer attachment section in the panel now; true file upload/storage is a separate decision (write-root blob store vs references only — §4.4) |
| SLA | **defined/hard**: `dueAt` required, sweep + escalation | **loose**: an optional `targetDate` attribute, surfaced with an overdue hint, no breach sweep/escalation |

Functional divergence to get there (roadmap order):

1. **C1 — Case = container, first-class.** Detail panel gains a **Contents** section: member
   incidents (from `CONTAINS` links) with status/priority badges, add/remove members, and roll-ups
   (open vs resolved members, worst severity, oldest member age). A case with open members warns on
   Resolve/Close (soft gate first; hard gate configurable later).
2. **C2 — Split & Merge** (this doc §3 — the requested feature).
3. **C3 — Findings (business fields, configurable).** A case **disposition** at resolution
   (config-driven list) + **impact** fields (amount, records/customers affected) + deployment-
   configurable extra sections (RcaTemplate-style TOON); member timeline auto-derived from linked
   incidents' events. The case counterpart of the incident postmortem (§2b).
4. **C4 — Case analytics.** Cases as a queryable Dataset (cycle time, backlog by category/queue,
   impact totals) → Studio widgets; the business-lens landing view.
5. **C5 — Rule-raised cases.** Reuse the Tag-Rule filter shape: "N incidents matching F within
   window W → open/attach to a case" (auto-grouping; keeps Alerts→Incidents→Cases mechanical).
6. **C6 — Case team + loose SLA + workflow-driven UI** (§2b): `attributes.assignees` team,
   `targetDate`, `GET /workflows/{type}` + folders/actions derived from the effective workflow.

Incident-side counterparts: **I1 — resolution gate** (§2b, the 4-section pattern + required SLA)
and **I2 — cause analysis generalized** (variable-length list, 5-Whys default).

Incidents keep: SLA, postmortem, Diagnosis linkage. Cases explicitly do NOT get postmortems
(they get Findings/disposition, C3); incidents do NOT get contents/split/merge or team assignment
(one Incident Commander).

## 3. Split & Merge (C2) — design

Both are **case-group management** over the existing link graph — no new stores.

### Vocabulary (GLOSSARY §9 additions when this ships)

- **Merge** — combine ≥2 Cases into one *surviving* Case managed as one.
- **Split** — carve member Incidents out of a Case into a new Case managed individually.
- New well-known `LinkRelationship`s: **`MERGED_INTO`** (absorbed → survivor), **`SPLIT_FROM`**
  (new part → original). Existing `CONTAINS` keeps expressing membership.

### Merge semantics (N cases → 1 survivor)

`POST /objects/{id}/merge` body `{sources: [caseId…], actor?}` — `{id}` is the **survivor**.

For each source case (all must be CASE type, not the survivor, not already merged):
1. **Members move:** every `CONTAINS` link from the source is re-pointed to the survivor
   (idempotent — a member already contained in the survivor is skipped).
2. **Metadata union:** tags (CSV union) and watchers (union) merge onto the survivor; the
   survivor's own fields (title, priority, assignee, category) are never overwritten.
3. **Trace:** a `MERGED_INTO` link source→survivor; `attributes.mergedInto = <survivorId>` on the
   source; one comment on each side ("merged CASE-a into CASE-b by actor"); `OBJECT_ACTIVITY` events.
4. **Source is closed, not deleted:** transitioned to `CLOSED` (terminal) — history, comments and
   attachments stay on the source, reachable via the link/graph. *(Chosen over a new `MERGED`
   state: no workflow surgery, and the marker + link make merged cases distinguishable; the UI can
   show a "merged into →" chip in Closed.)*

Errors: 404 unknown ids · 422 non-CASE, self-merge, already-merged source · 400 empty `sources`.

### Split semantics (1 case → 1+ new parts)

`POST /objects/{id}/split` body `{title, members: [incidentId…], assignee?|queue?, actor?}` —
repeatable for multi-way splits (each call carves one part).

1. A **new CASE** opens (workflow initial state `OPEN`) titled `{title}`, inheriting the
   original's category + tags (a split part starts in the same business bucket; operator can
   re-tag after).
2. The listed members' `CONTAINS` links move from the original to the new case (only members the
   original actually contains — unknown/foreign members → 422).
3. **Trace:** a `SPLIT_FROM` link new→original; comments on both; `OBJECT_ACTIVITY` events.
4. The original **stays active** with its remaining members (splitting everything out is allowed —
   the empty original is then closed manually or by the C1 soft gate; we do not auto-close).
5. Optional `assignee`/`queue` routes the new part (reuses `assign`).

Errors: 404 unknown case · 422 non-CASE, empty/foreign `members`, blank title.

### UI (Case Manager pane only)

- **Toolbar "Merge"** — enabled when ≥2 cases selected; dialog picks the survivor (radio, newest
  default) and previews the union (members/tags); confirm → one `merge` call.
- **Detail panel "Split…"** — dialog lists the case's member incidents (checkboxes) + name/assignee
  for the new part; disabled when the case has no members.
- **Contents section (C1)** in the case detail panel is the prerequisite surface both features hang
  off; ships with C2.
- Mock (`ops.handler`) mirrors both endpoints so the pane works offline, per the established
  pattern.

### Non-goals (now)

- Merging **incidents** (dedup is an alert/correlation concern upstream).
- Auto-merge suggestions ("similar cases" scoring) — C5's rule engine is the hook for later.
- Moving comments/attachments between cases on split (history stays where it happened; links
  carry the trail).

## 4. Open decisions (recommendations inline)

1. Survivor-close vs `MERGED` state — **recommended: CLOSED + `mergedInto` marker** (§3).
2. Hard vs soft open-member gate on case Resolve/Close — **recommended: soft (warning) first**.
3. Disposition list (C3): config-driven per deployment vs built-in default set — **recommended:
   built-in default + TOON override**, like workflows.
4. Case documents (§2b): true file upload (write-root blob store, size limits, content scanning
   concerns) vs richer reference-only attachments — **recommended: references now, upload as its
   own later design** (touches the air-gap/attack-surface posture, not a casual add).
5. Incident resolution gate (I1) rollout: UI-only soft-warn → UI hard-gate → backend workflow
   guard — **recommended: that order**, so operators adapt before the API enforces.
