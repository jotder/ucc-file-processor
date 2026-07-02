# Review sheet — Pipeline editor (Pipelines / Workbench)

**Wave:** 1 (Builder/Workbench) · **Date:** 2026-07-02 · **Files:**
`modules/admin/pipelines/{pipeline-editor.component.ts,.html,.spec.ts}` (existing spec unchanged) +
`pipeline-graph.ts` (+spec, extended) + new `pipeline-palette.component.ts` / `pipeline-inspector.component.ts`
(+specs)

Second Wave-1 item per the plan (`docs/superpower/frontend-review-and-completion-plan.md` §4): decompose the
752-line `pipeline-editor.component.ts` per W3 (push logic to framework-free libs, separate
container/presentational components).

## R1 — Glossary

No new concepts. Uses existing canonical terms: **Pipeline**, **Step** (a node), **Component**.

## R2 — Attribute audit

N/A — this pane is a canvas editor, not a config form; no `AttributeSpec`/tier work applies here.

## R3 — UX pass

No behavior changed for the operator — same toolbar, same palette trigger + panel, same property drawer,
same two-click connect / drag-drop / dry-run / validate flows. This review is purely structural (R4/R5);
a UX pass proper (toolbar icon density, the dry-run/validate panels' own disclosure) is flagged as a
**separate follow-on** in R7 rather than folded in here, to keep this change reviewable as "logic moved,
behavior identical."

## R4 — Reuse pass / component split

Two new presentational components carved out of the container, each self-contained and independently
tested (including a11y):

- **`PipelinePaletteComponent`** — the floating processor palette. Previously: a `paletteOpen` signal +
  a `paletteHeroIcon()` method on the container, plus ~50 lines of template. Now: fully self-contained
  (owns its own open/closed state), `[groups]` in, `(pick)` out. Drag-to-position needs no output — it
  writes `dataTransfer['text/flow-node-type']` directly, which `PipelineEditorGraphComponent`'s existing
  drop handler already reads; verified this contract still holds.
- **`PipelineInspectorComponent`** — the property drawer's three states (node selected / edge selected /
  idle hint). Previously ~75 lines of inline template reading `statusIcon()`/`statusTint()`/`configEntries()`
  container methods. Now: presentational, takes the pre-computed `status`/`category` (the container alone
  holds the registry-refs/test-outcome state `statusOf()` needs) plus the node/edge data, and five focused
  outputs (`configure`, `runToHere`, `connect`, `deleteSelected`, `edgeRelChange`).

## R5 — Logic extraction (the main win)

Fourteen functions moved from the component into the already-framework-free `pipeline-graph.ts`, each now
independently unit-tested without Angular/G6/TestBed:

- **The canvas edge-id codec** — `encodeEdgeId`/`decodeEdgeId` (`<from>-><to>:<rel>:<nonce>`), previously
  duplicated as ad-hoc string construction in three places and a regex in a fourth.
- **Six pure model reducers** — `addNodeToModel`, `addEdgeToModel`, `removeNodeFromModel`,
  `removeEdgeFromModel`, `setEdgeRelInModel`, `applyNodePatchInModel`. Each returns a **new**
  `AuthoredPipeline` (or `null` for a no-op, e.g. a duplicate edge) — the container's job shrinks to
  "apply the result, sync the canvas, mark dirty."
- **`uniqueNodeId`**, **`candidateRelsFor`**, **`nodeConfigEntries`** — the id-generation, edge-relationship-
  options, and config-row-formatting logic.
- **`statusIcon`/`statusTint`/`findingIcon`/`findingTint`/`paletteHeroIcon`** — five trivial-but-pure
  lookup switches that were private component methods for no reason (no Angular dependency).

## R6 — Mock contract

Unchanged — this pane already runs fully on the unified mock store (`pipelinesHandler`); no handler
changes were needed.

## R7 — Interview / honest scope note

The plan's aspirational target was "component ≤ ~150 lines." **Not met** — the container is now 685 lines
(down from 752, ~9%). Flagging why rather than padding the number: the bulk of the remaining size is
legitimate orchestration that doesn't belong in a pure lib or a presentational component — 6 HTTP-backed
CRUD flows (load/select/save/delete/create/activate), dialog opening (parser config / node config /
run-to-here), and ~10 canvas-sync handlers that call the `@ViewChild` G6 host directly. Splitting these
further would mean either fragmenting cohesive state across artificial sub-components (the same signals —
`model`, `selectedNode`, `dirty` — are read by nearly every handler) or introducing a service just to hold
state an Angular container already holds natively; both would cost more than they'd save, over-engineering
against this project's own "no abstractions for single-use code" rule. What *did* move — every pure
computation — is now independently testable and reusable, which was the substantive goal behind the line
count.

**Deferred, real candidates for a future pass** (not done here — scope control):
1. The **dry-run panel** and **validate-findings panel** (each ~30-40 template lines) could become their
   own presentational components the same way palette/inspector did, if this file needs to shrink further.
2. A UX pass on the toolbar's icon density (per plan §3's general Wave-1 mandate) — not attempted this
   round since it wasn't the assigned scope (decomposition, not redesign).

## R8 — Verify (evidence)

- **Live smoke** (`:4204`): opened the `cdr_ingest` pipeline in Edit mode via the app itself. Verified
  through the live component instance (canvas is G6/`<canvas>`, not scriptable — per the angular-ui skill's
  documented limitation):
  - Palette trigger → panel opens, lists groups/types (`PipelinePaletteComponent` renders correctly standalone).
  - Selecting the `parse` node → inspector shows `Node · parse`, category `Parser`, status `Configured`,
    `name:`, `CONFIG` rows (`delimiter`, `header`), and all four action buttons — byte-for-byte the same
    output as the pre-refactor inline template.
  - Selecting an edge → inspector shows the `Connection` relationship picker with the correct candidate
    list derived from `candidateRelsFor` (`data`, `success`, `failure` from the source node's emitted rels).
  - `setEdgeRel('failure')` → the model's edge updated via `setEdgeRelInModel`, `dirty` flagged.
  - `validate()` → findings computed correctly through the untouched `validatePipeline` pure function.
  - **Zero new console errors** across the whole exercise (6 total error entries in the session buffer, all
    pre-existing from the earlier parser-config bug-fix verification in this same session).
- **Automated**: `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **466 passed / 0 failed / 5 skipped**
  (baseline 441/0/5 — +25 from the extended `pipeline-graph.spec.ts` + the two new component specs).
  `pipeline-editor.component.spec.ts` was **not modified** — its 8 tests pass unchanged, confirming the
  container's public API (all signals + methods the spec exercises) held stable through the refactor.
- **Two genuine bugs the new tests caught before commit** (verification, not theatre):
  1. `encodeEdgeId` used `Date.now()` alone as its nonce — two edges encoded in the same millisecond
     (programmatic bulk-add) would collide. Hardened the **function itself** with a monotonic counter, not
     just the test. A latent bug in the original inline code, surfaced only by extracting + testing it.
  2. The inspector a11y spec called `TestBed.configureTestingModule` three times in one `it()` (once per
     state) — illegal after first instantiation. Fixed to reuse one fixture and mutate `@Input`s between
     assertions.

**Definition of Done: met** (with the R7 scope note above recorded rather than hidden).
