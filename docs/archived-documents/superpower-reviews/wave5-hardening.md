# Wave 5 — Hardening (release-candidate quality)

**Plan:** `frontend-review-and-completion-plan.md` §4 (Wave 5 row). **Scope (owner, 2026-07-05):**
**full-app exhaustive** a11y + responsive pass, plus design-system gallery refresh, icon/model-settings
sweep, and a closing GAUNTLET + bundle smoke. Multi-session — this sheet is the resumable checklist.

## Baseline (2026-07-05)
- `test:ci` **760 / 0 / 5**, stable across back-to-back runs after the vitest-timeout fix (`2a09352`).
- **87** files already carry `expectNoA11yViolations` — every route pane + most dialogs/shared primitives.
  The automated a11y gate is broadly in place; Wave 5 closes the remaining gaps + the responsive axis
  (jsdom axe can't test layout/overflow at breakpoints).

## P1 — a11y spec coverage gaps
Genuine project-UI gaps (vendored/template excluded). `[ ]` = todo, `[x]` = spec added.

### Skip (vendored / template — SKILL §3, do not audit)
`modules/auth/*` (default-callback, error404, error500) · `layout/layouts/*` (empty, classic, dense) ·
`layout/common/user` · `app.component` (bootstrap) · `layout/layout.component` (Fuse shell).

### Already covered (false positives — separate `*.a11y.spec.ts`)
`status-badge` · `skeleton` · `empty-state`.

### Dialogs
- [x] `catalog/node-detail.dialog` *(handoff-named gap — closed)*
- [x] `inspecto/components/assist.dialog`
- [x] `components/component-form.dialog`
- [x] `diagnoses/diagnosis-detail.dialog` *(a11y fix: copy button aria-label)*
- [x] `events/event-detail.dialog`
- [x] `jobs/job-run-detail.dialog`
- [x] `jobs/job-runs.dialog`
- [x] `objects/object-create.dialog`
- [x] `objects/object-link.dialog`
- [x] `run-detail/batch-detail.dialog`
- [x] `runs/reprocess.dialog`
- [x] `sources/source-detail.dialog`
- [x] `studio/link-analysis/element-detail.dialog`

### Panes / shared
- [x] `inspecto/components/assist-panel.component` *(handoff-named gap — closed)*
- [x] `design-system/design-system.component` (the `/design` gallery)
- [x] `model-settings/model-settings.component`
- [x] `inspecto/components/chart.component` (role=img + data-derived aria-label asserted)
- [x] `inspecto/components/connectivity-banner.component` (real ConnectivityService, shown+hidden states)

### Data-table internals
- [x] `inspecto/data-table/column-chooser.component`
- [x] `inspecto/data-table/sql/sql-editor.component`
- [x] `inspecto/data-table/sql/sql-codemirror.component` (CodeMirror mounts in jsdom; labelled via sqlEditorExtensions)

### Canvas host
- [x] `pipelines/pipeline-editor-graph.component` (G6 host — `rebuild()` stubbed, shell + emits tested)

## P2 — responsive sweep (375px mobile · 768px tablet) — DONE 2026-07-05
Preview-driven: every route SPA-navigated at both breakpoints, `body.scrollWidth` vs viewport measured,
top unclipped offender identified per failure. **32 routes checked · all green after fixes.**

**Findings & fixes (all template-only):**
1. **App-wide (every route): header toolbar overflow at 375px** (body 471px) — the `ml-auto` cluster
   (lens-switcher · space-switcher · bell · search · user) didn't fit. Fix: the lens/space switcher text
   labels collapse to icons below `sm:` (`hidden sm:inline`); both buttons already carry `aria-label`s.
2. **Rigid page-header rows** (`flex items-center justify-between`, no wrap): requirements ·
   reconciliation · connections (list) · expectations · spaces · studio/dashboards · connection-workbench —
   actions clusters pushed the body to 391–595px. Fix: `flex-wrap` + `gap-y-3` on the header row AND on the
   action cluster (three panes needed both — the cluster itself was >375px).
3. **Event-ticker rows** (dashboard `Recent events`, object-detail `Related events`): fixed `w-44`/`w-24`
   spans + message in a no-wrap row. Fix: `flex-wrap` + `min-w-0` on the message span.

At 768px all 32 routes were already clean (no tablet-specific defects). ag-Grid tables scroll within
their own container (by design, not a violation). Visual proof: /expectations at 375px — header wraps,
switchers icon-only, grid self-scrolls.

## P3 — /design gallery + icon/model-settings sweep — DONE 2026-07-05
- [x] `/design` Graph-hosts card refreshed: documents the read-only host's opt-in inputs (`[fill]` /
  `[display]` / `[tooltips]` / `[emphasis]` / `[layout]` with the 11-layout table + `isForest()` tree
  gating) and points at Link Analysis as the live everything-on example + the `graph-analysis.ts`
  algorithm library. Also fixed banned vocabulary in the card (*Flows* → *Pipelines*, per GLOSSARY).
- [x] icon-settings + model-settings swept: axe specs green (P1), all icon-only buttons labelled,
  no banned vocabulary in UI text. One stale comment fixed (icon-settings referenced `flow-graph`;
  the file is `pipeline-graph.ts`). Neither pane authors named artifacts ⇒ dup-guard/ask-the-minimum
  not applicable. Live-verified /design renders the new card at 375px, 0 console errors.

## P4 — final GAUNTLET + bundle smoke — DONE 2026-07-05
- [x] UI GAUNTLET: `lint:tokens` ✓ · `test:ci` **804 / 0 / 5** (one transient hit on the known
  `widget.kind.spec.ts` registry-isolation flake on the first pass; clean re-run, not a regression) ·
  prod `build` ✓ (22.8s, only the two documented pre-existing warnings).
- [x] Backend reactor: `mvn -o clean test` — BUILD SUCCESS, all 5 modules, **32 tests / 0 failures /
  0 errors**, 1:32 min. The two uncommitted side-task files (`ControlApi.java`,
  `ControlApiConnectionsTest.java`) were untouched and caused no failures (`ControlApiConnectionsTest`:
  2/0/0/0).
- [x] Bundle: `mvn -o clean package -DskipTests` → `file-processor-4.0.0-SNAPSHOT.jar` (96.4 MB) ·
  `package.ps1 -NoBuild` → succeeded first try, jlink runtime built clean (91.5 MB) — the previously-seen
  jlink rough edge did **not** reproduce this run · final `file-processor-deploy.zip` (160.5 MB).
- [x] Boot smoke: booted the bundle's jar directly (`--enable-native-access=ALL-UNNAMED
  -Dcontrol.port=8091 -Dspaces.root=spaces -Dui.dir=./ui`) — port 8080 was another session's server
  (left untouched) and the originally-planned 8090 was occupied by an unrelated Windows process
  (`WsToastNotification.exe`), so 8091 was used instead. `GET /health` → 200 `{"status":"UP"}`;
  `GET /` → 200 UI index HTML. Server killed cleanly afterward, port confirmed clear.
- [x] Sheet updated (this entry); memory index breadcrumb — see MEMORY.md.

**P4 overall: PASS.** Wave 5 (Hardening) is COMPLETE — release-candidate quality confirmed end to end
(lint → unit+a11y → prod build → backend reactor → packaged bundle → live boot from the artifact).

## Log
- 2026-07-05: sheet created; baseline captured; gap inventory computed (35 raw → ~22 in-scope after
  excluding vendored + already-covered).
- 2026-07-05: P1–P4 all closed same day. Commits: P1 `5eb7243` · P2 `3b2954a` · P3 `9c888a8` (P4 is a
  verification pass, no code diff to commit beyond this sheet).
</content>
