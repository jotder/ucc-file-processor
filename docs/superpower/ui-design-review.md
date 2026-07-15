# UI design review — Linear-bar UX audit & redesign roadmap

**Status:** REVIEW COMPLETE 2026-07-15 · **P1 (R1 + R2) SHIPPED same day** — grid-state persistence
(`stateKey` on data-table + tree-table, 8 hosts opted in), schema-form Enter-to-submit (`(submitted)`),
the shared dialog dirty-guard (`inspecto/dialog-dirty-guard.ts`, wired into the 5 schema-form dialogs),
and the `autocomplete` attribute type + `entity-option-loaders` (expectation target/refDataset, alert
metric/onPipeline, job onPipeline, decision-rule target). Deliberately deferred within R2: expectation
`column`/`refColumn` suggestions (need a per-target schema source) and object-create assignee/tags chips
(not schema-form-based). P2–P4 remain open. · **Method:** three parallel read-only surveys (forms/dialogs · data surfaces/persistence ·
navigation/keyboard) against the operator's design brief. · **Companions:** `tree-table-design.md`
(the quality bar) · `.claude/skills/angular-ui/SKILL.md` (the binding architecture rules) ·
`lens-access-config-design.md` (the newest pane, scored best-in-class here).

## 0. The brief (binding priorities)

Usability > information hierarchy > speed of operation > learnability > accessibility > consistency >
aesthetics. Linear-like feel: dense-but-readable, keyboard-first, progressive disclosure, persistent
preferences, context preservation, million-row readiness, investigation pivoting without redundant
navigation. Forms: minimize typing, autocomplete large sets, ask name/description at save, never
re-enter information, preserve entered data.

## 1. The quality bar — why the reconciliation tree-table works (properties to generalize)

The operator called out the recon tree-table as the reference feel. Its generalizable properties:

1. **Aligned value columns across hierarchy levels** — parent rollups and children share one numeric
   grid; Δ reads straight down a column. Hierarchy never costs comparability.
2. **State survives data churn** — expand state is a parent-owned `Set` kept across `nodes` refreshes
   (`linkedSignal`), stable `getRowId`; Resolve/edit never collapses the user's tree.
3. **Rollup semantics** — collapsed parents still communicate magnitude (summed signed diff), so the
   scan-summary-drill-only-where-needed loop works.
4. **One config-driven component, many jobs** — breakdown/comparison/recon/file-browser are just
   different `nodes`+`columns`; plugins are plain cell renderers. No per-use-case forks.
5. **Deliberate omission** — sorting is off because it would break parent→child order. Saying no to a
   feature to protect the reading model is exactly the brief's "never sacrifice usability".
6. **Density done right** — 13px grid, tight indents, icon-only toolbar, CSV escape hatch.

Every recommendation below converges other surfaces on these properties.

## 2. Scorecard — where the app stands today

**Strong (keep, generalize):**
- Access matrix (`modules/admin/access/`) — best-in-class editing: no typing (cycle cells), dirty
  tracking with Save/Discard/Reset, inherited values shown faded with provenance tooltips, search
  keeps ancestors, per-column counters, CSV export. This is the forms north star, not just the grids'.
- object-mail 3-pane — detail as a sibling pane (context preserved), nav collapsible + pointer- AND
  keyboard-resizable, width/collapsed persisted (`inspecto.mail.navWidth/navCollapsed`).
- Events **Saved Views** (`GET/POST /events/views`) — the app's only real filter persistence; the
  right idiom to generalize.
- Shared `<inspecto-data-table>` tiers, `@defer`'d CodeMirror, `SqlHistoryService`, lazy routes ×47,
  reconciliation `?path=` URL scope, connection-form two-step create with collapsed Routing.

**Systemic gaps (each detailed in §3):**

| # | Gap | Blast radius |
|---|---|---|
| G1 | Zero grid-state persistence (no `getColumnState`/`applyColumnState` anywhere) | ~40 grid hosts |
| G2 | Esc/Cancel silently discards form data; no dirty-guard, no drafts, anywhere | every dialog |
| G3 | Autocomplete used in zero forms; entity refs (pipeline/dataset/column/metric/assignee/tag) are free-text | ~8 forms |
| G4 | No Enter-to-submit in any schema-form dialog (schema-form has no `ngSubmit`) | ~12 dialogs |
| G5 | No global keyboard entry point — no Ctrl+K, no shortcut overlay, no list nav (j/k); palette is mouse-only to open | app-wide |
| G6 | Optimistic mutations inverted — instant on 2 peripheral toggles, spinner-bound on the whole incident/case triage loop | the core workflow |
| G7 | No server-side pagination anywhere — unbounded payloads (runs/jobs/catalog/connections) or silent hard caps (incidents 500, audit 2000, events 1000, db-browser 200) | high-volume panes |
| G8 | Context loss on routed details — runs/jobs detail drops scroll/selection/filters; settings section not URL-addressable; no breadcrumbs; space switch hard-reloads | runs, jobs, settings |
| G9 | Investigation dead-ends — link-analysis node & geo-map point cannot pivot to the underlying record/case | link-analysis, geo-map |
| G10 | Panel geometry persistence exists in exactly one pane and isn't extracted; mail detail pane fixed 30rem, recon/db-browser splits not resizable | 3-pane surfaces |

## 3. Redesign roadmap — ranked by the brief's priorities

Ordered by (impact on usability × breadth) / effort. Each item is independently committable.

### R1. Grid-state persistence in the shared tables — G1 (speed, usability; low effort, huge breadth)
Add `stateKey` input to `<inspecto-data-table>` and `<inspecto-tree-table>`. On grid events, persist
`api.getColumnState()` (width/order/visibility/sort) + toolbar state (`search`, `chosen`, filter-builder
`where`, page size) to `localStorage` keyed `inspecto.grid.<stateKey>.<spaceId>`; restore via
`applyColumnState`/`initialState` on ready. Tree-table also persists its `expanded` set per `stateKey`
(fixes expand loss on navigate-away, complementing the in-place fix already shipped). Hosts opt in with
one attribute; ~40 grids inherit it. Include a "Reset layout" item in the column chooser.

### R2. Schema-form triple fix — G2+G3+G4 (usability, forms brief; one seam, ~12 dialogs)
All three land in `<inspecto-schema-form>` + a thin dialog-host wrapper, not per-form:
- **Enter-to-submit:** give schema-form a real `<form (ngSubmit)>` and emit `(submitted)`; hosts bind
  the primary action once. (Reference pattern already proven in connection/space/component forms.)
- **Dirty-guard:** shared dialog opener sets `disableClose` when any child form is dirty; Esc/backdrop
  then asks via `InspectoConfirmService` ("Discard changes?"). Entered data is never silently lost.
- **`autocomplete` control type:** `AttributeSpec` gains `kind: 'autocomplete'` with an async
  `options$: (query) => Observable<AttributeOption[]>` loader. Then convert the free-text entity refs:
  expectation `target/column/refDataset/refColumn`, alert `metric/onPipeline`, job `onPipeline`,
  decision-rule `target` + dataset-driven columns (delete the hardcoded `RECORD_COLUMNS`), object-create
  `assignee` + `tags` (chips backed by the tag registry). This closes every "re-enter information the
  app already knows" case found.

### R3. Command palette + keyboard layer — G5 (keyboard-first, learnability)
- `Ctrl/Cmd+K` app-level listener in the classic layout focuses the existing search palette; extend
  `SearchDestination` with **action commands** (New incident, Trigger job, Switch lens/space, Toggle
  theme) and recents (localStorage). `/` focuses the nearest grid quick-filter.
- `?` opens a shortcut cheat-sheet overlay (revive the commented-out `ShortcutsComponent` slot as a
  simple dialog listing live bindings).
- List keyboard nav on detail-feeding tables — `j/k`/arrows move a focused row, Enter opens detail,
  `x` toggles selection — implemented once in `<inspecto-data-table>` (opt-in `[keyNav]`), piloted on
  the incidents mail list where the side panel makes it Linear-grade triage.

### R4. Make the triage loop instant — G6 (speed where it counts)
Route object-mail toolbar verbs (accept/resolve/archive/reopen/escalate/prioritize/tag) through
`optimisticMutate` patching the loaded rows in place (reassign array) instead of `bulk()`→full
`reload()`. Keep request→refetch for merge/split/create (server-computed). The most-used loop in the
app becomes zero-latency; failures roll back with a toast per the existing idiom.

### R5. Context preservation on routed details — G8 (usability, hierarchy)
- Runs & jobs: convert detail to the proven side-panel (object-mail) or dialog (events) pattern —
  recommendation: **side panel**, since runs are scanned serially; keep a routed URL (`/runs/:name`)
  that opens the panel over the restored list so deep links still work.
- Settings: bind the master-detail selection to a `:section` route param (deep-linkable, Back works,
  refresh doesn't reset).
- Shared breadcrumb strip on remaining routed details (list → id), per the skill's §4 rule already on
  the books but unimplemented.

### R6. Honest high-volume loading — G7 (million-row brief)
Two steps, cheapest first: (a) every capped pane surfaces "showing N of M — Load more" instead of
silently truncating (incidents 500, audit 2000, events 1000, db-browser 200); unbounded lists
(runs/jobs) gain a server `limit+offset`. (b) The genuinely high-volume panes (events, audit,
db-browser, runs) move to paged fetch behind the data-table — an opt-in `[serverPage]` mode mirroring
the existing `[serverRun]` seam — before any pane needs the full ag-Grid server row model.

### R7. Resizable-pane primitive — G10 (density, investigation layouts)
Extract object-mail's pointer+keyboard resize + localStorage persistence into a shared
`inspecto-split` directive/component (`[stateKey]`, min/max, `role="separator"` a11y kept). Apply to:
mail detail pane (today fixed 30rem), data-browser master/detail, recon side panes. This is the
enabler for consistent 3-pane investigation surfaces.

### R8. Investigation pivots — G9 (investigation-centric brief)
- Link-analysis `ElementDetailDialog` and geo-map selection gain "Open record / Open case" actions
  routing to the object detail with the id (and, per R5, without destroying the graph/map state
  behind them).
- Longer-term (design-only for now): a shared "pivot bar" contract — table/graph/map/timeline views
  over the same selection — is the natural growth of the drill-drawer pattern; do not build until two
  concrete hosts need it.

### R9. Form polish sweep (after R2 lands)
- Reconciliation form: migrate `[(ngModel)]` → reactive + `<mat-error>` (the one form with zero inline
  validation), wrap in `ngSubmit`.
- Component form: validate the transform JSON textarea on blur; partitions CSV → chips.
- Two-step "name at save" adoption for the remaining create dialogs (job/expectation/alert/
  decision-rule currently ask the immutable id first) — follow connection-form; pre-fill
  `<type>_<context>` ids.
- `cdkFocusInitial` on the first field of every dialog (today none set it).

## 4. Deliberate non-goals

- No visual-theme rework — density and tokens are already right; aesthetics is the lowest priority
  and the current 13px grid language is the Linear feel already.
- No ag-Grid Enterprise, no NgRx, no new heavy deps (skill §0.5).
- No compact/comfortable density toggle yet — uniform density is fine until a user asks.
- Space-switch hard reload stays — it is a deliberate isolation boundary, not a UX bug.
- The pivot-bar framework (R8 second half) stays design-only until demanded twice.

## 5. Suggested phasing (independently committable, each with the standard UI DoD)

| Phase | Items | Why first |
|---|---|---|
| P1 | R1 (grid state) + R2 (schema-form triple) | Two single-seam changes with the widest blast radius; pure usability |
| P2 | R3 (palette + keyboard) + R4 (optimistic triage) | The "feels like Linear" moment; both localized |
| P3 | R5 (context) + R7 (split primitive) | Structural; R7 unblocks R5's side panels |
| P4 | R6 (honest paging) + R8 (pivots) + R9 (form sweep) | Backend-touching / long-tail polish |

Every phase: `lint:tokens` → prod build → `test:ci` (+ new axe specs) → offline preview walk, per the
angular-ui skill §12; `/design` gallery + skill updated when a shared pattern changes.
