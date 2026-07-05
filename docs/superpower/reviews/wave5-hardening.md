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

## P3 — /design gallery + icon/model-settings sweep
- [ ] `/design`: add the new shared-host primitives from this stretch (GraphView `[layout]` picker; note the
  algorithm/pattern toolbox lives in Link Analysis, not a shared primitive).
- [ ] icon-settings + model-settings: a11y labels, form rules (ask-the-minimum / dup-guard where authoring).

## P4 — final GAUNTLET + bundle smoke
- [ ] `lint:tokens` · prod `build` · `test:ci` · `package.ps1` bundle smoke (boots from the artifact).
- [ ] Update this sheet, memory index breadcrumb; handoff.

## Log
- 2026-07-05: sheet created; baseline captured; gap inventory computed (35 raw → ~22 in-scope after
  excluding vendored + already-covered).
</content>
