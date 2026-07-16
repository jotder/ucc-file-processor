# Review sheet — Components / Registry (Studio / Wave 2)

**Wave:** 2 (Builder: Studio) · **Date:** 2026-07-03 · **Files:**
`modules/admin/catalog/{registry.component.ts,.html,.spec.ts, components-data-provider.ts,
platform-kinds.ts}` + shared `inspecto/graph/graph-view.component.ts`, `inspecto/component-model`
(`deriveComponentGraph`).

**Registry / reuse-graph** — a single lens over every registered component kind (dataset, widget,
dashboard, grammar, schema, transform, sink, rule, plus authored pipelines). The relationship graph is
**derived**, not a separate store: each composite's `parts` are read from its own config (a widget → its
dataset; a dashboard → its widget tiles; a pipeline node's `use=<kind>/<id>` ref), rendered through the
shared `GraphViewComponent`, with a node-click detail panel and a flat reference table. **Read-only** — no
authoring here (each kind is edited via its own pane), so the form rules (ask-the-minimum, dup-guard)
don't apply.

## R1 — Glossary

**Component** (the metamodel noun — never "Data Store"), **Kind** (Type), **Part**/**Reference** (the
composition edges the reuse-graph draws), **Registry** (this pane's name, matches the plan's "Components/
Registry" label). All canonical. No GLOSSARY change.

## R2 — Attribute audit

Not a form — a derived read model. The columns it exposes (component/kind/usedBy/relationship in the
reference table; kind/name/references in the detail panel) match what `deriveComponentGraph` actually
produces. Nothing speculative.

## R3 — UX pass

Single `<h2>` (sub-header under the Catalog module's own `<h1>` — consistent with the module having
multiple tabs/panes), icon-only Refresh with `aria-label`, a real graph view, a node-detail panel (kind +
name + an "Open" edit-link when the kind has an editor + its outgoing references or a "leaf component"
note), a dangling-reference notice for ghost nodes, and a reference table. No structural change needed.

## R4 — Reuse pass

Already on the design system: `<inspecto-empty-state>` (no components yet), `<inspecto-graph-view>`
(shared G6 host, read-only pattern per the angular-ui skill), `<inspecto-data-table tier="standard">` for
the reference table (with its own `noRowsTitle`/`noRowsHint`, not a second bespoke empty state). No
hardcoded colors — the graph's node/edge coloring goes through the shared `canvasTheme()`/`nodeColor`
seam (not touched by this pane directly). No violations found.

## R5 — Logic extraction

Already correctly separated: `deriveComponentGraph` lives in the framework-free `component-model` lib
(its own tests); this component's only logic is the load orchestration (`Promise.allSettled` across
kinds + pipelines) and two small pure-ish helpers (`partsFor`, `pipelineParts`, `splitRef`) that are
already free functions at module scope, not methods — easy to extract further only if a second consumer
needs them, which none does today. 184 lines is legitimate load/derive orchestration, not bloat.

## R6 — Mock contract

Runs on the unified `MockStore` via `ComponentsDataProvider` (per-kind `list`) + `PipelinesService`
(authored flows); a kind's list failure is individually tolerated (`Promise.allSettled`) so one broken
kind doesn't blank the whole graph — matches the project's "independent fetches degrade gracefully" rule.
No new endpoint.

## R7 — Interview / decisions made

1. **Read-only by design** — components are authored in their own editors (dataset/widget/dashboard
   editors, the pipeline editor); this pane is purely the cross-kind relationship lens. Consistent with
   every other read-only Studio/Workbench lens reviewed this wave.
2. **A `ComponentKind.deriveParts` seam is noted but not built** (pre-existing comment in `partsFor`) —
   today `partsFor`/`pipelineParts` hardcode the known composite shapes (widget→dataset,
   dashboard→tiles, pipeline node `use=` refs). Flagged as a future formalization if a third composite
   kind needs the same reference-derivation logic; not warranted for two consumers.

## R8 — Verify (evidence)

- **Already compliant** — a spec existed and covers derivation (dashboard→widget→dataset), pipeline
  node-ref derivation, dangling-reference ghost nodes, and an a11y assertion on the empty state. No code
  changes were needed; this sheet is a compliance confirmation, not a fix log.
- **Automated:** `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **491 passed / 0 failed / 5 skipped**
  (unchanged from the prior Wave-2 baseline — no new/modified test cases in this review).
- **Live smoke** (`:4204`): Registry renders the reuse graph over seeded datasets/widgets/dashboards/
  pipelines; clicking a node shows kind/name/references + an "Open" link to the owning editor for
  editable kinds; the reference table lists the same edges. No console errors.

**Definition of Done: met** — pane confirmed compliant with no code changes required.
